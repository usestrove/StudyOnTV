package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.min

/** 本轮主阶段是否已达标（nextQuestion 与 answer 的唯一真理源）。 */
internal fun isBaseDoneImpl(round: JSONObject): Boolean {
    val target = round.optInt("target")
    return when (round.optString("mode")) {
        "streak" -> round.optInt("streak") >= target
        "count" -> round.optInt("correct") >= target
        else -> round.optInt("answered") >= target
    }
}

/** 是否处于自主学习/非管控轮次（纯刷题模式、自由时段或主动发起的学习）。 */
internal fun isVoluntaryImpl(round: JSONObject, supervisionEnabled: Boolean): Boolean =
    round.optBoolean("voluntary") || !supervisionEnabled

/** 当前轮次是否启用错题加练机制。 */
internal fun isExtraEnabledImpl(round: JSONObject, supervisionEnabled: Boolean): Boolean {
    if (isVoluntaryImpl(round, supervisionEnabled)) return false
    if (round.optString("mode") == "count") return false
    return round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
}

/** 当前是否应进入错题加练出题阶段（统一约束 extraEnabled、extraPool 与 baseDone）。 */
internal fun isExtraStageImpl(round: JSONObject, extraPool: Long, supervisionEnabled: Boolean): Boolean {
    if (!isExtraEnabledImpl(round, supervisionEnabled) || extraPool <= 0L) return false
    return round.optString("mode") == "practice" || isBaseDoneImpl(round)
}

/** 从精准加练队列中挑选下一道有效题号，同时收集已失效（被删或暂停）的题号以便清理。 */
internal fun selectNextExtraQueueIdImpl(
    queueIds: List<String>,
    lastId: String?,
    isValid: (String) -> Boolean
): Pair<String?, List<String>> {
    val stale = mutableListOf<String>()
    var fallback: String? = null
    for (qid in queueIds) {
        if (!isValid(qid)) {
            stale.add(qid)
            continue
        }
        if (qid != lastId) return qid to stale
        if (fallback == null) fallback = qid
    }
    return fallback to stale
}

internal fun planClearExtraActiveRoundImpl(
    round: JSONObject?,
    supervisionEnabled: Boolean
): LearningEngine.Companion.ClearExtraRoundAction {
    if (round == null || round.has("pendingReview")) return LearningEngine.Companion.ClearExtraRoundAction.NONE
    val mode = round.optString("mode")
    if (mode == "practice") {
        return if (round.optBoolean("extra")) LearningEngine.Companion.ClearExtraRoundAction.CLEAR_EXTRA_FLAG
        else LearningEngine.Companion.ClearExtraRoundAction.NONE
    }
    if (!isBaseDoneImpl(round)) {
        return if (round.optBoolean("extra")) LearningEngine.Companion.ClearExtraRoundAction.CLEAR_EXTRA_FLAG
        else LearningEngine.Companion.ClearExtraRoundAction.NONE
    }
    val rules = round.optJSONObject("rules")
    val passRatio = rules?.optDouble("passRatio", 0.6) ?: 0.6
    val passed = mode in listOf("streak", "count") ||
        round.optInt("correct") >= ceil(round.optInt("target") * passRatio).toInt()
    return if (passed) LearningEngine.Companion.ClearExtraRoundAction.SETTLE_PASS
    else LearningEngine.Companion.ClearExtraRoundAction.SETTLE_FAIL
}

/** ST-601：将错题库中选中的有效题目去重加入精准加练队列，并同步增加加练池计数。 */
internal fun LearningEngine.addWrongToExtraPoolImpl(ids: List<String>): JSONObject {
    day()
    val db = store.writableDatabase
    var added = 0
    var nextPool = 0L
    var queue: List<String> = emptyList()
    db.beginTransaction()
    try {
        added = store.addToExtraQueue(ids)
        queue = store.extraQueue()
        val currentPool = number("extra_pool")
        nextPool = maxOf(currentPool + added.toLong(), queue.size.toLong())
        put("extra_pool", nextPool)
        if (added > 0) {
            store.logEvent("extra_added", JSONObject().put("count", added).put("source", "wrong_book"))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        publishNavigation()
    }
    return JSONObject()
        .put("ok", true)
        .put("added", added)
        .put("extraPool", nextPool)
        .put("extraQueueIds", JSONArray(queue))
        .put("learning", status())
}

/** ST-602：原子清空加练池、错题累积进度与精准加练队列；若当前轮次处于加练阶段则妥善收敛不卡死。 */
internal fun LearningEngine.clearExtraPoolImpl(): JSONObject {
    day()
    val db = store.writableDatabase
    var cleared = 0L
    db.beginTransaction()
    try {
        cleared = number("extra_pool")
        put("extra_pool", 0)
        put("wrong_progress", 0)
        store.clearExtraQueue()

        val round = active()
        val config = store.config()
        val supervisionEnabled = config.optBoolean("supervisionEnabled", true)
        when (LearningEngine.planClearExtraActiveRound(round, supervisionEnabled)) {
            LearningEngine.Companion.ClearExtraRoundAction.SETTLE_PASS,
            LearningEngine.Companion.ClearExtraRoundAction.SETTLE_FAIL -> {
                val r = round!!
                val passed = LearningEngine.planClearExtraActiveRound(r, supervisionEnabled) ==
                    LearningEngine.Companion.ClearExtraRoundAction.SETTLE_PASS
                val voluntary = LearningEngine.isVoluntary(r, supervisionEnabled)
                val rules = r.optJSONObject("rules") ?: config
                var grant = 0L
                if (passed && !voluntary) {
                    val requested = if (rules.optBoolean("difficultyRewardEnabled")) r.optLong("earnedMs")
                    else rules.optLong("sessionMinutes", 10) * 60000L
                    val budget = time.dailyLimit(limitConfig(config)) - number("used_ms") - number("credit_ms") - freeBalance()
                    grant = min(requested, budget.coerceAtLeast(0))
                    put("credit_ms", number("credit_ms") + grant)
                    store.logEvent("quiz_pass", JSONObject().put("roundId", r.optString("id")).put("minutes", grant / 60000.0))
                }
                store.logEvent("quiz", JSONObject().put("native", true).put("voluntary", voluntary)
                    .put("total", r.optInt("answered")).put("correct", r.optInt("correct")).put("passed", passed))
                store.set("active_round", "")
                put("quiz_generation", number("quiz_generation") + 1)
            }
            LearningEngine.Companion.ClearExtraRoundAction.CLEAR_EXTRA_FLAG -> {
                val r = round!!
                r.put("extra", false)
                store.set("active_round", r.toString())
                put("quiz_generation", number("quiz_generation") + 1)
            }
            LearningEngine.Companion.ClearExtraRoundAction.NONE -> Unit
        }

        store.logEvent("extra_pool_cleared", JSONObject().put("cleared", cleared))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
        publishNavigation()
    }
    return JSONObject()
        .put("ok", true)
        .put("cleared", cleared)
        .put("extraPool", 0)
        .put("learning", status())
}
