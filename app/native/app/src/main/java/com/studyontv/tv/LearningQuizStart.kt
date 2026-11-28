package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 闪卡自评的纯状态机计划：在写库前统一完成轮次匹配、卡组归属与同轮幂等去重校验。 */
internal fun planFlashcardRateImpl(
    round: JSONObject?,
    roundId: String,
    qid: String,
    quality: Int
): LearningEngine.Companion.FlashcardRatePlan {
    val q = quality.coerceIn(1, 5)
    val correct = q >= 3
    if (roundId.isNotEmpty()) {
        require(round != null && round.optString("id") == roundId) { "任务已更新，请重新加载" }
    }
    if (round == null) {
        return LearningEngine.Companion.FlashcardRatePlan(q, correct, alreadyRated = false, finished = false, emptyList(), emptyList(), 0)
    }
    val deck = round.optJSONArray("studyDeck")
    val deckIds = LinkedHashSet<String>()
    if (deck != null) {
        for (i in 0 until deck.length()) {
            val id = deck.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotEmpty()) deckIds.add(id)
        }
    }
    if (deckIds.isNotEmpty()) {
        require(qid in deckIds) { "该题目不在当前闪卡轮次中" }
    }
    val ratedArr = round.optJSONArray("ratedIds") ?: JSONArray()
    val ratedSet = LinkedHashSet<String>()
    for (i in 0 until ratedArr.length()) {
        val id = ratedArr.optString(i)
        if (id.isNotEmpty()) ratedSet.add(id)
    }
    val correctArr = round.optJSONArray("ratedCorrectIds") ?: JSONArray()
    val correctSet = LinkedHashSet<String>()
    for (i in 0 until correctArr.length()) {
        val id = correctArr.optString(i)
        if (id.isNotEmpty()) correctSet.add(id)
    }
    if (qid in ratedSet) {
        return LearningEngine.Companion.FlashcardRatePlan(q, correct, alreadyRated = true, finished = false, ratedSet.toList(), correctSet.toList(), deckIds.size)
    }
    ratedSet.add(qid)
    if (correct) correctSet.add(qid) else correctSet.remove(qid)
    val finished = round.optString("focus") == "memorize" && deckIds.isNotEmpty() && ratedSet.containsAll(deckIds)
    return LearningEngine.Companion.FlashcardRatePlan(q, correct, alreadyRated = false, finished = finished, ratedSet.toList(), correctSet.toList(), deckIds.size)
}

internal fun LearningEngine.startImpl(options: JSONObject): JSONObject {
    val st = status()
    val c = store.config()
    val supervision = c.optBoolean("supervisionEnabled", true)
    val requestedLevel = options.optString("level").trim().takeIf { it.isNotEmpty() }
    val requestedSubject = options.optString("subject").trim()
    val requestedBank = options.optString("bank").trim()
    val focus = options.optString("focus").trim()
    require(focus in listOf("", "due", "wrong", "memorize", "quiz")) { "未知学习模式" }
    val explicitStudy = options.optBoolean("forceStart", false) || focus.isNotEmpty() ||
        requestedSubject.isNotEmpty() || requestedBank.isNotEmpty() || options.has("memorizeFirst")
    val wantMemorizeFirst = when {
        focus == "memorize" -> true
        options.has("memorizeFirst") -> options.optBoolean("memorizeFirst")
        else -> c.optBoolean("memorizeFirst", false)
    }

    if (st.getString("reason") == "feedback") {
        val held = JSONObject(store.get("feedback_result").takeIf { it.isNotEmpty() } ?: "{}")
        if (!held.has("question")) {
            store.set("feedback_deadline", "")
            store.set("feedback_result", "")
            return start(options)
        }
        return held.put("heldFeedback", true).put("status", st)
    }
    if (supervision && st.getString("reason") in listOf("locked", "rest")) {
        return JSONObject().put("blocked", st.getString("reason")).put("status", st)
    }
    if (supervision && !explicitStudy && st.optBoolean("free") && c.getString("playMode") != "practice") {
        return JSONObject().put("free", true).put("status", st).put("study", store.studySummary())
    }
    val feedbackId = store.get("review_feedback")
    val feedback = feedbackId.takeIf { it.isNotEmpty() }?.let { store.review(it) }
    if (feedback?.optString("state") == "graded") return JSONObject().put("reviewed", true).put("status", st)
        .put("reviewId", feedbackId).put("score", feedback.getDouble("score"))
        .put("feedback", feedback.optString("feedback")).put("result", feedback.getJSONObject("result"))
    if (supervision && !explicitStudy && c.getString("playMode") != "practice" && st.getString("reason") in listOf("quota", "rounds", "schedule")) {
        return JSONObject().put("blocked", st.getString("reason")).put("status", st)
    }
    val hasWatchQuota = st.optLong("remainingSeconds") > 0L || st.optLong("freeQuotaSeconds") > 0L
    if (supervision && !explicitStudy && hasWatchQuota && c.getString("playMode") != "practice") {
        return JSONObject().put("watching", true).put("status", st).put("study", store.studySummary())
    }
    var round = active()
    healPendingReview(round)
    if (!explicitStudy && round == null && supervision && videos.wanted(c)) {
        return JSONObject().put("videoLesson", videos.start(c)).put("status", st)
    }
    if (round?.has("pendingReview") == true) {
        return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", st)
    }
    if (round != null && round.optInt("answered") == 0 && round.optJSONObject("rules")?.optInt("version") != c.optInt("version")) {
        round = null
    }
    // 自主学习（无管控或空闲时从学习主页点不同专项）允许切换专项；强制解锁中的半途任务不允许重置逃课
    if (round != null && explicitStudy && (!supervision || round.optBoolean("voluntary"))) {
        val changed = (focus.isNotEmpty() && focus != round.optString("focus")) ||
            requestedSubject != round.optString("subjectFilter") ||
            requestedBank != round.optString("bankFilter") ||
            (options.has("memorizeFirst") && wantMemorizeFirst != round.optBoolean("memorizeFirst"))
        if (changed) round = null
    }

    val voluntary = !supervision || st.optBoolean("free") || hasWatchQuota ||
        st.getString("reason") in listOf("quota", "rounds", "schedule")
    val desiredMode = if (voluntary && c.getString("playMode") == "practice" && !wantMemorizeFirst && focus.isEmpty()) "practice"
        else if (voluntary && focus.isNotEmpty()) "round"
        else c.getString("playMode")

    if (round == null || (!round.optBoolean("voluntary") && round.optString("mode") != c.getString("playMode"))) {
        var selectedLevel = ""
        val subjects = if (requestedSubject.isNotEmpty()) listOf(requestedSubject) else scopeValues(c, "quizSubjects")
        val hasExplicitSubject = requestedSubject.isNotEmpty()
        val banks = if (requestedBank.isNotEmpty()) listOf(requestedBank) else scopeValues(c, "quizBanks")
        val allowedLevels = scopeValues(c, "quizLevels")
        val gradeSubjects = LocalStore.parseGradeSubjects(c.optJSONObject("gradeSubjects"))
        if (c.optBoolean("childSelectLevel")) {
            val choices = store.distinctLevels(
                allowedLevels = allowedLevels,
                subjects = subjects,
                banks = banks,
                gradeSubjects = gradeSubjects,
                explicitSubjectFilter = hasExplicitSubject
            )
            require(choices.isNotEmpty()) { "请先为启用题目标注年级，或关闭孩子选择年级" }
            if (requestedLevel == null && choices.size > 1) return JSONObject().put("chooseLevel", JSONArray(choices)).put("status", st)
            selectedLevel = requestedLevel ?: choices.single()
            require(selectedLevel in choices) { "请选择家长允许的年级" }
        }

        if (focus == "wrong") {
            val sampleWrong = store.pickQuestion(
                subjects = subjects, levels = allowedLevels, selectedLevel = selectedLevel,
                banks = banks, onlyWrong = true,
                gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject
            )
            if (sampleWrong == null) {
                return JSONObject().put("wrongEmpty", true).put("status", st).put("study", store.studySummary())
            }
        }

        round = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("mode", desiredMode)
            .put("voluntary", voluntary)
            .put("focus", focus)
            .put("subjectFilter", requestedSubject)
            .put("bankFilter", requestedBank)
            .put("memorizeFirst", wantMemorizeFirst)
            .put("level", selectedLevel)
            .put("target", c.getInt("questionsPerRound"))
            .put("answered", 0).put("correct", 0)
            .put("streak", 0).put("earnedMs", 0).put("rules", c)

        if (wantMemorizeFirst) {
            val deckCount = c.getInt("questionsPerRound").coerceIn(1, 20)
            val spaced = c.optBoolean("spacedRepetitionEnabled") || focus in listOf("due", "memorize")
            val spacedWrongOnly = c.optString("spacedScope") == "wrong_only"
            val deck = when {
                focus == "wrong" -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = spaced, wrongFirst = true, onlyWrong = true, gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                spaced && spacedWrongOnly -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = true, missedOnly = true, gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                    .takeIf { it.length() > 0 }
                    ?: store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = true, coverageFirst = c.optBoolean("coverageFirst"), newFirst = c.optBoolean("newFirst"), gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
                else -> store.pickDeck(deckCount, subjects, allowedLevels, selectedLevel, banks, spaced = spaced, wrongFirst = c.optBoolean("wrongFirst"), coverageFirst = c.optBoolean("coverageFirst"), newFirst = c.optBoolean("newFirst"), gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject)
            }
            require(deck.length() > 0) {
                if (subjects.isNotEmpty() || allowedLevels.isNotEmpty() || banks.isNotEmpty() || gradeSubjects.isNotEmpty())
                    "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围"
                else "题库为空，请由家长添加题目"
            }
            round.put("target", deck.length())
                .put("studyPhase", "memorize")
                .put("studyDeck", deck)
                .put("deckIndex", -1)
                .put("ratedIds", JSONArray())
                .put("ratedCorrectIds", JSONArray())
                .put("question", deck.getJSONObject(0))
                .put("nonce", UUID.randomUUID().toString())
        } else {
            round.put("studyPhase", "quiz")
            nextQuestion(round)
        }
        store.set("active_round", round.toString())
    }
    return publicRound(round)
}

/** 「先背再测」背诵完毕后切入闭卷小测阶段（纯闪卡模式下也可直接结束本组背诵）。 */
internal fun LearningEngine.memorizedImpl(roundId: String): JSONObject {
    val round = active() ?: return JSONObject().put("error", "round_missing")
    if (roundId.isNotEmpty()) require(round.optString("id") == roundId) { "任务已更新，请重新加载" }
    if (round.optString("focus") == "memorize") {
        val deckLen = round.optJSONArray("studyDeck")?.length() ?: 0
        val correctLen = round.optJSONArray("ratedCorrectIds")?.length() ?: deckLen
        store.logEvent("quiz", JSONObject().put("native", true).put("memorize", true).put("total", deckLen).put("correct", correctLen).put("passed", true))
        store.set("active_round", "")
        publishNavigation()
        return JSONObject().put("ok", true).put("finished", true).put("status", status()).put("study", store.studySummary())
    }
    if (round.optString("studyPhase") == "memorize") {
        round.put("studyPhase", "quiz").put("deckIndex", -1)
        nextQuestion(round)
        store.set("active_round", round.toString())
        store.logEvent("memorize_completed", JSONObject().put("roundId", round.optString("id")))
    }
    return publicRound(round)
}

/** Anki 风格闪卡自评：1=重来(10分钟)、3=困难、4=良好、5=简单。 */
internal fun LearningEngine.rateFlashcardImpl(roundId: String, qid: String, quality: Int): JSONObject {
    require(qid.isNotBlank() && store.questionExists(qid)) { "题目不存在" }
    val round = active()
    val plan = LearningEngine.planFlashcardRate(round, roundId, qid, quality)
    if (plan.alreadyRated) {
        return JSONObject()
            .put("ok", true)
            .put("alreadyRated", true)
            .put("finished", false)
            .put("intervals", store.previewIntervals(qid))
            .put("status", status())
            .put("study", store.studySummary())
    }
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        store.recordProgress(qid, plan.quality)
        store.answered(qid, plan.correct)
        store.logEvent("flashcard", JSONObject().put("qid", qid).put("quality", plan.quality).put("correct", plan.correct))

        if (round != null) {
            round.put("ratedIds", JSONArray(plan.updatedRatedIds))
            round.put("ratedCorrectIds", JSONArray(plan.updatedCorrectIds))

            if (plan.finished) {
                store.logEvent("quiz", JSONObject().put("native", true).put("memorize", true)
                    .put("total", plan.deckSize).put("correct", plan.updatedCorrectIds.size).put("passed", true))
                store.set("active_round", "")
                db.setTransactionSuccessful()
                return JSONObject()
                    .put("ok", true)
                    .put("finished", true)
                    .put("intervals", store.previewIntervals(qid))
                    .put("status", status())
                    .put("study", store.studySummary())
            }
            store.set("active_round", round.toString())
        }
        db.setTransactionSuccessful()
        return JSONObject()
            .put("ok", true)
            .put("finished", false)
            .put("intervals", store.previewIntervals(qid))
            .put("status", status())
            .put("study", store.studySummary())
    } finally {
        db.endTransaction()
        publishNavigation()
    }
}

internal fun LearningEngine.videoProgressImpl(id: String, playing: Boolean): JSONObject {
    val state = status()
    require(state.optString("reason") !in listOf("locked", "rest", "schedule", "quota", "rounds", "feedback")) { "当前暂不可进行视频学习" }
    return videos.progress(id, playing)
}

internal fun LearningEngine.completeVideoImpl(id: String): JSONObject {
    videoProgress(id, false)
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        videos.complete(id)
        val config = store.config()
        val supervision = config.optBoolean("supervisionEnabled", true)
        val grant = if (!supervision) 0L else minOf(
            config.optLong("sessionMinutes", 10) * 60000,
            (time.dailyLimit(limitConfig(config)) - number("used_ms") - number("credit_ms") - freeBalance()).coerceAtLeast(0)
        )
        if (grant > 0) put("credit_ms", number("credit_ms") + grant)
        store.logEvent("quiz", JSONObject().put("native", true).put("video", true).put("total", 0).put("correct", 0).put("passed", true))
        if (supervision) store.logEvent("quiz_pass", JSONObject().put("roundId", id).put("minutes", grant / 60000.0).put("video", true))
        val result = JSONObject().put("rewardMinutes", grant / 60000.0).put("status", status())
        db.setTransactionSuccessful()
        return result
    } finally { db.endTransaction(); publishNavigation() }
}
