package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

internal fun LearningEngine.continueFeedbackImpl(): JSONObject {
    require(time.left("feedback_deadline") == 0L) { "请先等待解析倒计时结束" }
    store.set("feedback_result", "")
    store.set("feedback_deadline", "")
    return JSONObject().put("status", status())
}

internal fun LearningEngine.acknowledgeReviewImpl(id: String): JSONObject {
    if (store.get("review_feedback") == id) store.set("review_feedback", "")
    return JSONObject().put("ok", true).put("status", status())
}

internal fun LearningEngine.skipPendingImpl(): JSONObject {
    val round = active() ?: return JSONObject().put("error", "round_missing")
    val id = round.optString("pendingReview")
    if (id.isEmpty()) return JSONObject().put("pending", false).put("status", status())
    store.review(id)?.let {
        it.put("state", "skipped").put("feedback", "孩子选择先跳过，等待家长处理").put("skippedAt", store.clockReading().millis)
        store.saveReview(it)
    }
    round.remove("pendingReview")
    nextQuestion(round)
    store.set("active_round", round.toString())
    store.logEvent("review_skipped", JSONObject().put("id", id))
    Diag.warn("Quiz", "review skipped id=$id")
    return publicRound(round)
}

internal fun LearningEngine.gradeImpl(reviewId: String, score: Double, feedback: String, source: String): JSONObject {
    require(score.isFinite() && score in 0.0..100.0) { "分数需要在 0–100 之间" }
    val review = store.review(reviewId) ?: throw IllegalArgumentException("待批题目不存在")
    require(review.getString("state") != "skipped") { "这道题已被跳过，不再批改" }
    if (review.getString("state") == "graded") return review
    val reviewChild = review.optString("child", LocalStore.DEFAULT_CHILD)
    require(store.childExists(reviewChild)) { "该题目所属的孩子档案已删除" }
    val settings = JSONObject(store.get("grading_settings", "{}"))
    val threshold = settings.optDouble("passScore", 60.0)
    if (reviewChild != store.activeChild()) flushUsage()
    store.withChild(reviewChild) { day() }
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        // 即使电视当前已切换到另一个孩子，批改奖励、错题统计、复习进度与通知也必须结清到原答题孩子名下
        store.withChild(reviewChild) {
            val quality = when { score < threshold -> 1; score >= 90 -> 5; score >= 75 -> 4; else -> 3 }
            val result = answer(review.getJSONObject("input"), score >= threshold, quality)
            require(!result.has("error") && !result.has("blocked")) { "当前任务暂不可结算，请在可用时段重试" }
            review.put("state", "graded").put("score", score).put("feedback", feedback.take(5000))
                .put("source", source).put("gradedAt", store.clockReading().millis).put("result", result)
            store.saveReview(review)
            store.set("review_feedback", reviewId)
            store.logEvent("review_graded", JSONObject().put("id", reviewId).put("score", score).put("source", source))
        }
        db.setTransactionSuccessful()
        return review
    } finally { db.endTransaction(); publishNavigation() }
}

internal fun LearningEngine.answerImpl(input: JSONObject, verdict: Boolean?, quality: Int?): JSONObject {
    day()
    val last = store.get("last_answer")
    if (verdict == null && last.isNotEmpty()) {
        val cached = JSONObject(last)
        if (cached.optString("nonce") == input.optString("nonce")) return cached.getJSONObject("result")
    }
    val round = active() ?: return JSONObject().put("error", "round_missing")
    if (verdict == null && round.has("pendingReview")) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", status())
    if (verdict == null && round.optJSONObject("question")?.optString("id")?.let { !store.questionExists(it) } == true) {
        Diag.warn("Quiz", "作答的题目已被删除，重新出题")
        store.logEvent("answer_question_deleted", JSONObject().put("roundId", round.optString("id")))
        val replaced = try { nextQuestion(round); true } catch (e: Exception) { Diag.warn("Quiz", "删题后无题可用: ${e.message}"); false }
        if (replaced) store.set("active_round", round.toString())
        else for (key in listOf("active_round", "last_answer", "feedback_result", "feedback_deadline", "review_feedback")) store.set(key, "")
        put("quiz_generation", number("quiz_generation") + 1)
        val recovered = JSONObject().put("recovered", true).put("finished", false).put("correct", false)
            .put("correctIndices", JSONArray()).put("status", status())
        if (!replaced) recovered.put("quizEmpty", true).put("quizEmptyMessage", "题库为空，请由家长添加题目")
        return recovered
    }
    require(input.getString("roundId") == round.getString("id") && input.getString("nonce") == round.getString("nonce")) { "题目已更新，请重新加载" }
    val st = status()
    val supervisionEnabled = store.config().optBoolean("supervisionEnabled", true)
    val voluntary = LearningEngine.isVoluntary(round, supervisionEnabled)
    if (verdict == null && !voluntary && round.getString("mode") != "practice" &&
        st.getString("reason") in listOf("schedule", "quota", "rounds", "rest", "feedback", "locked")) {
        return JSONObject().put("blocked", st.getString("reason"))
    }
    val question = round.getJSONObject("question")
    val type = question.optString("type", "single")
    if (verdict == null && (type == "subjective" || input.optString("drawing").isNotEmpty())) {
        val rawDrawing = input.optString("drawing").trim()
        require(input.optString("text").isNotBlank() || rawDrawing.isNotEmpty()) { "请先作答" }
        require(
            rawDrawing.isEmpty() ||
                MediaStore.isMediaPath(rawDrawing) ||
                (rawDrawing.startsWith("data:image/png;base64,", ignoreCase = true) && rawDrawing.length <= 2_000_000)
        ) { "手写图片格式不正确或过大" }
        val savedDrawing = when {
            rawDrawing.isEmpty() -> ""
            MediaStore.isMediaPath(rawDrawing) -> rawDrawing
            else -> MediaStore.saveDataUrl(store.mediaDir, rawDrawing, 2_000_000)
        }
        val cleanInput = JSONObject(input.toString()).apply {
            if (savedDrawing.isEmpty()) remove("drawing") else put("drawing", savedDrawing)
        }
        val id = UUID.randomUUID().toString()
        val review = JSONObject().put("id", id).put("state", "pending").put("ts", store.clockReading().millis)
            .put("child", store.activeChild())
            .put("question", question).put("input", cleanInput).put("feedback", "等待批改")
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            store.saveReview(review)
            round.put("pendingReview", id)
            store.set("active_round", round.toString())
            store.logEvent("review_submitted", JSONObject().put("id", id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); publishNavigation() }
        return JSONObject().put("pending", true).put("reviewId", id).put("status", status())
    }
    val expected = when (type) {
        "multiple" -> question.getJSONArray("correctIndices")
        "text", "subjective" -> JSONArray()
        else -> JSONArray().put(question.optInt("k"))
    }
    fun indices(array: JSONArray): Set<Int> = (0 until array.length()).map { array.getInt(it) }.toSet()
    val correct = if (verdict != null) verdict else if (type == "text") {
        val rawText = input.optString("text")
        val accepted = question.getJSONArray("answers")
        rawText.isNotBlank() && (0 until accepted.length()).any { LearningEngine.matchesTextAnswer(rawText, accepted.getString(it)) }
    } else {
        val selections = input.getJSONArray("selections")
        require(selections.length() > 0 && (0 until selections.length()).all { selections.getInt(it) in 0 until question.getJSONArray("a").length() }) { "请选择有效答案" }
        indices(expected) == indices(selections)
    }
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        round.remove("pendingReview")
        val rules = round.getJSONObject("rules")
        val wasExtra = round.optBoolean("extra")
        val mode = round.getString("mode")
        val extraEnabled = LearningEngine.isExtraEnabled(round, supervisionEnabled)
        if (!correct && extraEnabled) {
            var wrong = number("wrong_progress") + 1
            if (wrong >= rules.getInt("wrongThreshold")) {
                val extra = rules.getLong("extraQuestions")
                put("extra_pool", number("extra_pool") + extra)
                wrong -= rules.getInt("wrongThreshold")
                store.logEvent("extra_added", JSONObject().put("count", extra))
            }
            put("wrong_progress", wrong)
        }
        if (correct && wasExtra && extraEnabled && number("extra_pool") > 0) put("extra_pool", number("extra_pool") - 1)
        if (correct) store.popExtraQueue(question.getString("id"))
        if (mode == "streak" && !correct && !wasExtra) round.put("earnedMs", 0)
        if (!wasExtra || mode == "practice") {
            round.put("answered", round.getInt("answered") + 1)
            if (correct) round.put("correct", round.getInt("correct") + 1)
            round.put("streak", if (correct) round.getInt("streak") + 1 else 0)
            if (correct && rules.optBoolean("difficultyRewardEnabled")) {
                val minutes = rules.getJSONObject("difficultyRewards").getDouble(question.optInt("difficulty", 2).toString())
                round.put("earnedMs", round.getLong("earnedMs") + (minutes * 60000).toLong())
            }
        }
        store.recordProgress(question.getString("id"), quality ?: if (correct) 4 else 1)
        store.answered(question.getString("id"), correct)
        store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
            .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
        val baseDone = LearningEngine.isBaseDone(round)
        val passed = mode in listOf("streak", "count") || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
        val finished = mode != "practice" && baseDone && (!passed || !extraEnabled || number("extra_pool") == 0L)
        val answerText = when (type) {
            "text" -> question.optJSONArray("answers")?.optString(0).orEmpty()
            "subjective" -> question.optString("modelAnswer")
            else -> ""
        }
        val result = JSONObject()
            .put("correct", correct)
            .put("correctIndices", if (type == "text" || type == "subjective") JSONArray() else expected)
            .put("answerText", answerText)
            .put("tip", question.optString("tip"))
            .put("finished", finished)
            .put("passed", finished && passed)
        if (finished) {
            var grant = 0L
            if (passed && !voluntary) {
                val requested = if (rules.optBoolean("difficultyRewardEnabled")) round.getLong("earnedMs") else rules.getLong("sessionMinutes") * 60000
                val budget = time.dailyLimit(limitConfig(store.config())) - number("used_ms") - number("credit_ms") - freeBalance()
                grant = min(requested, budget.coerceAtLeast(0))
                put("credit_ms", number("credit_ms") + grant)
                store.logEvent("quiz_pass", JSONObject().put("roundId", round.getString("id")).put("minutes", grant / 60000.0))
            }
            store.logEvent("quiz", JSONObject().put("native", true).put("voluntary", voluntary)
                .put("total", round.getInt("answered")).put("correct", round.getInt("correct")).put("passed", passed))
            store.set("active_round", "")
            result.put("rewardMinutes", grant / 60000.0)
        } else {
            var aborted = false
            if (mode == "count" && !correct) {
                round.put("nonce", UUID.randomUUID().toString())
                store.presented(question.getString("id"))
            } else {
                try { nextQuestion(round) } catch (e: IllegalArgumentException) {
                    aborted = true
                    Diag.warn("Quiz", "无法生成下一题，作废本轮: ${e.message}")
                    store.logEvent("round_aborted", JSONObject().put("reason", "empty_bank").put("message", e.message ?: ""))
                    store.set("active_round", "")
                    put("quiz_generation", number("quiz_generation") + 1)
                    result.put("quizEmpty", true).put("quizEmptyMessage", e.message ?: "题库为空，请由家长添加题目")
                }
            }
            if (!aborted) {
                store.set("active_round", round.toString())
                result.put("next", publicRound(round))
            }
        }
        if (!correct && rules.optInt("wrongReviewSeconds") > 0) {
            time.deadline("feedback_deadline", rules.optInt("wrongReviewSeconds") * 1000L)
            val sanitizedQuestion = JSONObject(question.toString()).apply {
                LearningEngine.SECRET_QUESTION_FIELDS.forEach { remove(it) }
            }
            store.set("feedback_result", JSONObject().put("result", JSONObject(result.toString()))
                .put("question", sanitizedQuestion).toString())
        }
        result.put("status", status()).put("study", store.studySummary())
        store.set("last_answer", JSONObject().put("nonce", input.getString("nonce")).put("result", result).toString())
        db.setTransactionSuccessful()
        return result
    } finally { db.endTransaction(); publishNavigation() }
}
