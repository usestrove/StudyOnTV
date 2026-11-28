package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun LearningEngine.active(): JSONObject? =
    store.get("active_round").takeIf { it.isNotEmpty() }?.let { JSONObject(it) }

internal fun LearningEngine.healPendingReview(round: JSONObject?) {
    val id = round?.optString("pendingReview")?.takeIf { it.isNotEmpty() } ?: return
    val review = store.review(id)
    val state = review?.optString("state") ?: "missing"
    if (state == "pending") return
    round.remove("pendingReview")
    store.set("active_round", round.toString())
    if (state == "graded") store.set("review_feedback", id)
    store.logEvent("review_healed", JSONObject().put("id", id).put("state", state))
    Diag.warn("Quiz", "pendingReview 已失效（$state），自动恢复 id=$id")
}

/** 家长删题或暂停题目后修补进行中的一轮：孩子手上的题失效时立刻换成可用题目并升高 quiz_generation。 */
internal fun LearningEngine.repairActiveRound(removed: Set<String>, allCleared: Boolean): Boolean {
    val round = active() ?: return false
    val question = round.optJSONObject("question")
    val pendingId = round.optString("pendingReview")
    val reviewQuestionId = pendingId.takeIf { it.isNotEmpty() }?.let { store.review(it) }?.optJSONObject("question")?.optString("id") ?: ""
    val deck = round.optJSONArray("studyDeck")
    val deckHit = deck != null && (allCleared || (0 until deck.length()).any { deck.optJSONObject(it)?.optString("id") in removed })
    val hit = allCleared || deckHit || (question != null && question.optString("id") in removed) || (reviewQuestionId.isNotEmpty() && reviewQuestionId in removed)
    if (!hit) return false
    if (pendingId.isNotEmpty()) {
        store.review(pendingId)?.let { review ->
            if (review.optString("state") == "pending") {
                review.put("state", "skipped").put("feedback", "家长删除了这道题")
                store.saveReview(review)
            }
        }
        round.remove("pendingReview")
    }
    if (deck != null) {
        val filtered = JSONArray()
        if (!allCleared) {
            for (i in 0 until deck.length()) {
                val card = deck.optJSONObject(i) ?: continue
                if (card.optString("id") !in removed) filtered.put(card)
            }
        }
        if (filtered.length() == 0) {
            round.remove("studyDeck")
            round.put("studyPhase", "quiz")
        } else {
            round.put("studyDeck", filtered)
            round.put("target", minOf(round.optInt("target", filtered.length()), filtered.length()))
            if (round.optString("studyPhase") == "memorize") {
                round.put("question", filtered.getJSONObject(0)).put("nonce", UUID.randomUUID().toString())
                store.set("active_round", round.toString())
                put("quiz_generation", number("quiz_generation") + 1)
                return true
            }
        }
    }
    val replaced = try { nextQuestion(round); true } catch (e: Exception) {
        Diag.warn("Quiz", "题目删空或已全部暂停，作废进行中的一轮: ${e.message}")
        false
    }
    if (replaced) {
        store.set("active_round", round.toString())
        store.logEvent("round_replaced", JSONObject().put("reason", "questions_deleted"))
    } else {
        for (key in listOf("active_round", "last_answer", "feedback_result", "feedback_deadline", "review_feedback")) store.set(key, "")
        store.logEvent("round_cleared", JSONObject().put("reason", "questions_deleted"))
    }
    put("quiz_generation", number("quiz_generation") + 1)
    return true
}

internal fun LearningEngine.deleteQuestionsImpl(ids: List<String>): Int {
    val db = store.writableDatabase
    var committed = false
    val deleted: Int
    db.beginTransaction()
    try {
        deleted = store.deleteQuestions(ids)
        repairActiveRound(ids.toSet(), false)
        db.setTransactionSuccessful()
        committed = true
    } finally { db.endTransaction(); publishNavigation() }
    if (committed) store.pruneMedia()
    return deleted
}

internal fun LearningEngine.setQuestionsEnabledImpl(ids: List<String>, enabled: Boolean): Int {
    val db = store.writableDatabase
    val updated: Int
    db.beginTransaction()
    try {
        updated = store.setEnabled(ids, enabled)
        if (!enabled) repairActiveRound(ids.toSet(), false)
        db.setTransactionSuccessful()
    } finally { db.endTransaction(); publishNavigation() }
    return updated
}

internal fun LearningEngine.onExternalQuestionsRemovedImpl(ids: Set<String>): Boolean {
    if (ids.isEmpty()) return false
    val db = store.writableDatabase
    val repaired: Boolean
    db.beginTransaction()
    try {
        repaired = repairActiveRound(ids, false)
        db.setTransactionSuccessful()
    } finally { db.endTransaction(); publishNavigation() }
    return repaired
}

internal fun LearningEngine.clearQuestionsImpl(bank: String?): Int {
    val db = store.writableDatabase
    var committed = false
    val deleted: Int
    db.beginTransaction()
    try {
        val removed = if (bank == null) emptySet() else store.idsInBank(bank).toSet()
        deleted = if (bank == null) store.clearQuestions() else store.deleteQuestions(removed.toList())
        repairActiveRound(removed, bank == null)
        db.setTransactionSuccessful()
        committed = true
    } finally { db.endTransaction(); publishNavigation() }
    if (committed) store.pruneMedia()
    return deleted
}

internal fun scopeValues(config: JSONObject, key: String): List<String> {
    val array = config.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
}

internal fun effectiveSubjects(config: JSONObject, round: JSONObject): List<String> {
    val specific = round.optString("subjectFilter").trim()
    return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizSubjects")
}

internal fun effectiveBanks(config: JSONObject, round: JSONObject): List<String> {
    val specific = round.optString("bankFilter").trim()
    return if (specific.isNotEmpty()) listOf(specific) else scopeValues(config, "quizBanks")
}

internal fun LearningEngine.nextQuestion(round: JSONObject) {
    val config = store.config()
    val subjects = effectiveSubjects(config, round)
    val hasExplicitSubject = round.optString("subjectFilter").trim().isNotEmpty()
    val levels = scopeValues(config, "quizLevels")
    val selectedLevel = round.optString("level")
    val banks = effectiveBanks(config, round)
    val gradeSubjects = LocalStore.parseGradeSubjects(config.optJSONObject("gradeSubjects"))
    val focus = round.optString("focus")
    val spaced = config.optBoolean("spacedRepetitionEnabled") || focus == "due"
    val spacedWrongOnly = config.optString("spacedScope") == "wrong_only"
    val wrongFirst = config.optBoolean("wrongFirst") || focus == "wrong"
    val coverageFirst = config.optBoolean("coverageFirst")
    val newFirst = config.optBoolean("newFirst")

    val supervisionEnabled = config.optBoolean("supervisionEnabled", true)
    val extraStage = LearningEngine.isExtraStage(round, number("extra_pool"), supervisionEnabled)

    // 「先背再测」进入小测阶段后，按刚背过的卡组逐题考查；只有触发错题加练后才回到题库随机补题
    val deck = round.optJSONArray("studyDeck")
    if (!extraStage && round.optString("studyPhase") == "quiz" && deck != null && deck.length() > 0) {
        var idx = round.optInt("deckIndex", -1) + 1
        while (idx < deck.length()) {
            val candidate = deck.optJSONObject(idx)
            val cid = candidate?.optString("id").orEmpty()
            if (cid.isNotEmpty() && store.questionExists(cid)) {
                val presented = if (config.optBoolean("shuffleOptions")) QuestionOptions.shuffled(candidate!!) else JSONObject(candidate!!.toString())
                store.presented(presented.getString("id"))
                round.put("deckIndex", idx).put("question", presented).put("nonce", UUID.randomUUID().toString()).put("extra", false)
                return
            }
            idx++
        }
    }

    val last = round.optJSONObject("question")?.optString("id")?.takeIf { it.isNotEmpty() }

    // ST-601：加练阶段优先从家长精准加入的 extra_queue 取有效题目，自动清理已删或已暂停的失效项
    var queuedQuestion: JSONObject? = null
    if (extraStage) {
        val queueIds = store.extraQueue()
        if (queueIds.isNotEmpty()) {
            val fetched = HashMap<String, JSONObject>()
            val (pickedId, staleIds) = LearningEngine.selectNextExtraQueueId(queueIds, last) { qid ->
                val found = store.questionById(qid, enabledOnly = true)
                if (found != null) fetched[qid] = found
                found != null
            }
            for (stale in staleIds) store.popExtraQueue(stale)
            if (pickedId != null) queuedQuestion = fetched[pickedId]
        }
    }

    fun query(exclude: String?, onlyWrong: Boolean = false, missedOnly: Boolean = false): JSONObject? =
        store.pickQuestion(
            subjects = subjects, levels = levels, selectedLevel = selectedLevel, banks = banks,
            spaced = spaced, wrongFirst = wrongFirst, coverageFirst = coverageFirst, newFirst = newFirst,
            excludeId = exclude, onlyWrong = onlyWrong, missedOnly = missedOnly,
            gradeSubjects = gradeSubjects, explicitSubjectFilter = hasExplicitSubject
        )

    val question = queuedQuestion ?: when {
        focus == "wrong" -> query(last, onlyWrong = true) ?: query(null, onlyWrong = true)
            ?: query(last) ?: query(null)
        wrongFirst && extraStage -> query(last, onlyWrong = true)
            ?: (if (spaced && spacedWrongOnly) query(last, missedOnly = true) else null)
            ?: query(last) ?: query(null)
        spaced && spacedWrongOnly -> query(last, missedOnly = true) ?: query(null, missedOnly = true)
            ?: query(last) ?: query(null)
        else -> query(last) ?: query(null)
    } ?: throw IllegalArgumentException(
        if (subjects.isNotEmpty() || levels.isNotEmpty() || banks.isNotEmpty() || gradeSubjects.isNotEmpty())
            "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围"
        else "题库为空，请由家长添加题目"
    )

    val presented = if (config.optBoolean("shuffleOptions")) QuestionOptions.shuffled(question) else question
    store.presented(presented.getString("id"))
    round.put("question", presented).put("nonce", UUID.randomUUID().toString())
    round.put("extra", extraStage)
}

internal fun LearningEngine.buildStudyCards(deck: JSONArray, ratedIds: Set<String>): JSONArray {
    val cards = JSONArray()
    for (i in 0 until deck.length()) {
        val item = deck.optJSONObject(i) ?: continue
        val qid = item.optString("id")
        val card = JSONObject()
            .put("id", qid)
            .put("q", item.optString("q"))
            .put("type", item.optString("type", "single"))
            .put("a", item.optJSONArray("a") ?: JSONArray())
            .put("tip", item.optString("tip"))
            .put("language", item.optString("language", ""))
            .put("image", item.optString("image"))
            .put("audio", item.optString("audio"))
            .put("subject", item.optString("subject"))
            .put("level", item.optString("level"))
            .put("answerDisplay", LearningEngine.formatAnswerDisplay(item))
            .put("intervals", store.previewIntervals(qid))
            .put("rated", qid in ratedIds)
        item.optJSONArray("optionImages")?.let { card.put("optionImages", it) }
        item.optJSONArray("images")?.let { card.put("images", it) }
        cards.put(card)
    }
    return cards
}

internal fun LearningEngine.publicRound(round: JSONObject): JSONObject {
    val copy = JSONObject(round.toString())
    copy.optJSONObject("question")?.let { question ->
        LearningEngine.SECRET_QUESTION_FIELDS.forEach { question.remove(it) }
    }
    if (copy.optString("studyPhase") == "memorize") {
        val deck = round.optJSONArray("studyDeck") ?: JSONArray()
        val ratedArr = round.optJSONArray("ratedIds") ?: JSONArray()
        val ratedSet = (0 until ratedArr.length()).map { ratedArr.optString(it) }.toSet()
        copy.put("studyCards", buildStudyCards(deck, ratedSet))
    }
    copy.remove("studyDeck")
    copy.remove("rules")
    return copy.put("extraPool", number("extra_pool")).put("status", status()).put("study", store.studySummary())
}
