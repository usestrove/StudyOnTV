package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.min

/**
 * 纯函数扣时规划：按 free_quota_ms -> boot_quota_ms -> credit_ms 顺序无缝扣减，
 * 当免费额度耗尽时把余数继续扣到 credit_ms，不丢尾数。
 */
internal fun planUsageDeductionImpl(
    requestedMs: Long,
    availableMs: Long,
    freeQuotaMs: Long,
    bootQuotaMs: Long,
    creditMs: Long,
    maxSingleMs: Long
): LearningEngine.Companion.UsageDeduction {
    var left = min(requestedMs.coerceIn(0L, maxSingleMs.coerceAtLeast(0L)), availableMs.coerceAtLeast(0L))
    val freeConsumed = min(left, freeQuotaMs.coerceAtLeast(0L))
    left -= freeConsumed
    val bootConsumed = min(left, bootQuotaMs.coerceAtLeast(0L))
    left -= bootConsumed
    val creditConsumed = min(left, creditMs.coerceAtLeast(0L))
    return LearningEngine.Companion.UsageDeduction(freeConsumed, bootConsumed, creditConsumed)
}

internal fun hasUsableWatchQuotaImpl(remainingMs: Long, freeQuotaMs: Long): Boolean =
    (remainingMs / 1000L) > 0L || (freeQuotaMs / 1000L) > 0L

internal fun computeCanExitImpl(
    supervision: Boolean,
    reason: String,
    remainingMs: Long,
    freeQuotaMs: Long,
    free: Boolean
): Boolean = !supervision || (reason.isEmpty() && (free || hasUsableWatchQuotaImpl(remainingMs, freeQuotaMs)))

/** 今天还能扣掉的额度：每日上限 - 已用，并且不能超过「距离必须休息」的额度。 */
internal fun LearningEngine.capacity(config: JSONObject): Long {
    if (time.left("feedback_deadline") > 0) return 0
    return minOf(
        (time.dailyLimit(limitConfig(config)) - number("used_ms")).coerceAtLeast(0),
        time.beforeRest(config)
    )
}

internal fun LearningEngine.freeBalance(): Long = number("free_quota_ms") + number("boot_quota_ms")

internal fun LearningEngine.usableFree(config: JSONObject): Long = min(freeBalance(), capacity(config))

internal fun LearningEngine.usableCredit(config: JSONObject): Long =
    min(number("credit_ms"), (capacity(config) - usableFree(config)).coerceAtLeast(0))

internal fun LearningEngine.day() {
    val db = store.writableDatabase
    // 结算在开启事务前确定用量日期，事务中的 status() 不能重新日切。
    if (db.inTransaction()) return
    val clock = store.clockReading()
    val current = usageDayStart(clock.millis)
    val previousUsageDay = store.get("usage_day").toLongOrNull()
    val previousSource = store.get("usage_day_source")
    val rejectedSystemDay = store.get("usage_day_rejected_system").toLongOrNull()
    val networkTime = clock.source == DeviceClock.Source.NETWORK
    // 时钟再次回退到系统时间时，已被纠正的未来日期不能再次触发日切。
    val rejectedFallback = !networkTime && rejectedSystemDay != null && current >= rejectedSystemDay

    // 无来源的旧版记账日也可能来自错误的系统时间。校正日期时沿用本次账本，不重新发放额度。
    if (networkTime && previousUsageDay != null && current < previousUsageDay &&
        previousSource != DeviceClock.Source.NETWORK.id) {
        db.beginTransaction()
        try {
            put("usage_day_rounds", store.roundsDoneToday().toLong())
            put("usage_day", current)
            store.set("usage_day_source", clock.source.id)
            put("usage_day_rejected_system", minOf(previousUsageDay, rejectedSystemDay ?: previousUsageDay))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Diag.warn("Day", "网络时间校正未来记账日，保留额度 previous=$previousUsageDay current=$current")
    } else if (previousUsageDay == null || (current > previousUsageDay && !rejectedFallback)) {
        db.beginTransaction()
        try {
            if (pendingUsageMs > 0) store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
            val rounds = if (previousUsageDay == null) store.roundsInDay(current) else 0
            put("usage_day", current)
            put("usage_day_rounds", rounds.toLong())
            store.set("usage_day_source", clock.source.id)
            if (networkTime && rejectedSystemDay != null && current >= rejectedSystemDay) {
                store.set("usage_day_rejected_system", "")
            }
            put("credit_ms", 0)
            put("used_ms", 0)
            put("cap_adjust_ms", 0)
            store.set("video_lesson", "")
            if (time.left("rest_deadline") == 0L) put("rest_watched_ms", 0)
            val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
            put("free_quota_ms", freeMin * 60000L)
            put("free_used_ms", 0)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pendingUsageMs = 0
        return
    } else {
        // 部分设备冷启动会先使用出厂日期，校时后才恢复。回拨时保留原来的用量日期和余额。
        if (current < previousUsageDay && lastLoggedRollbackDay != previousUsageDay) {
            lastLoggedRollbackDay = previousUsageDay
            Diag.warn("Day", "检测到日期回拨，保留记账日和额度 previous=$previousUsageDay current=$current")
        }
        if (networkTime && current == previousUsageDay && previousSource != clock.source.id) {
            store.set("usage_day_source", clock.source.id)
        }
    }
    if (store.get("free_used_ms").isEmpty()) {
        // 旧版本升级：没有 free_used_ms，用当天已用时长估算已消耗的免费额度。
        val daily = store.config().optLong("dailyFreeMinutes", 0L) * 60000L
        put("free_used_ms", min(number("used_ms"), daily).coerceAtLeast(0))
    }
}

internal fun LearningEngine.saveConfigImpl(input: JSONObject): JSONObject {
    day()
    val previousDaily = store.config().optLong("dailyFreeMinutes") * 60000L
    val previousFree = number("free_quota_ms")
    val usedFree = number("free_used_ms")
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        val config = store.saveConfig(input)
        val daily = config.optLong("dailyFreeMinutes") * 60000L
        val granted = (previousFree + (daily - previousDaily)).coerceAtLeast(0)
        val cap = (daily - usedFree).coerceAtLeast(0)
        put("free_quota_ms", min(granted, cap))
        db.setTransactionSuccessful()
        return config
    } finally { db.endTransaction(); publishNavigation() }
}

/** 纯刷题背诵模式（supervisionEnabled=false）下不启用时长锁屏，优先于一切管控规则放行。 */
internal fun LearningEngine.freeNow(c: JSONObject, point: ScheduleWindow? = activeWindow(c)): Boolean {
    if (!c.optBoolean("supervisionEnabled", true)) return true
    val o = override()
    if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
    if (point != null) return point.mode != "counted"
    return scheduleFree(c)
}

internal fun LearningEngine.statusImpl(): JSONObject {
    day()
    val inTransaction = store.writableDatabase.inTransaction()
    val c = store.config()
    val supervision = c.optBoolean("supervisionEnabled", true)
    val remaining = usableCredit(c)
    val used = number("used_ms")
    val rounds = store.roundsDoneToday()
    val o = if (supervision) override() else null
    val point = if (supervision) activeWindow(c) else null
    val limit = if (point?.mode == "counted") JSONObject(c.toString()).put("dailyMinutes", point.dailyMinutes) else c
    val free = freeNow(c, point)
    val freeQuota = usableFree(c)
    val rest = if (supervision) time.restLeft(c) else 0L
    val hold = time.left("feedback_deadline")
    val reason = when {
        !supervision -> if (hold > 0) "feedback" else ""
        o?.optString("mode") == "lock" -> "locked"
        free -> ""
        rest > 0 -> "rest"
        hold > 0 -> "feedback"
        used >= time.dailyLimit(limit) -> "quota"
        c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
        freeQuota > 0 -> ""
        c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") && remaining <= 0 -> "rounds"
        c.optBoolean("roundsLimitEnabled", true) && rounds >= c.getInt("roundsPerDay") -> ""
        remaining > 0 -> ""
        else -> "timeup"
    }
    val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
        else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
    // 每日免费、开机免费和奖励额度都可以放行返回键和 Home 键。
    val canExit = LearningEngine.computeCanExit(supervision, reason, remaining, freeQuota, free)
    val activeRound = active()
    val result = JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
        .put("supervisionEnabled", supervision)
        .put("quizGeneration", number("quiz_generation"))
        .put("activeRoundId", activeRound?.optString("id").orEmpty())
        .put("activeNonce", activeRound?.optString("nonce").orEmpty())
        .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
        .put("nextAvailableAt", nextAt).put("nextAtText", if (nextAt > 0) DeviceClock.format(nextAt, store) else "").put("free", free)
        .put("restSeconds", (rest + 999) / 1000).put("feedbackSeconds", (hold + 999) / 1000)
        .put("todayLimitMinutes", if (supervision && c.optBoolean("dailyLimitEnabled", true)) time.dailyLimit(limit) / 60000.0 else JSONObject.NULL)
        .put("bootFreeSeconds", min(number("boot_quota_ms"), freeQuota) / 1000)
        .put("freeQuotaSeconds", freeQuota / 1000)
        .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", activeRound?.has("pendingReview") == true)
        .put("activePoint", point?.let { JSONObject().put("name", it.name).put("mode", it.mode).put("until", it.until) } ?: JSONObject.NULL)
        .put("canExit", canExit)
        .put("scheduleText", scheduleText(c))
    // 事务内的 status 不能提前对外暴露未提交的奖励或解锁状态
    if (!inTransaction)
        updateExitSnapshot(LearningExitSnapshot(canExit, SystemClock.elapsedRealtime()))
    return result
}

internal fun LearningEngine.consumeImpl(milliseconds: Long) {
    try { consumeUsage(milliseconds) } finally { publishNavigation() }
}

internal fun LearningEngine.consumeAndStatusImpl(milliseconds: Long): JSONObject {
    try {
        consumeUsage(milliseconds)
    } catch (e: Exception) {
        publishNavigation()
        throw e
    }
    return status()
}

internal fun LearningEngine.consumeUsage(milliseconds: Long) {
    day()
    val config = store.config()
    if (!config.optBoolean("supervisionEnabled", true)) { flushUsage(); return }
    if (time.restLeft(config) > 0 || time.left("feedback_deadline") > 0) { flushUsage(); return }
    if (freeNow(config)) { flushUsage(); return }
    if (config.optString("scheduleMode", "allow") != "control" && !inWindow(config)) { flushUsage(); return }
    val freeQuotaNow = number("free_quota_ms")
    val bootQuotaNow = number("boot_quota_ms")
    val creditNow = number("credit_ms")
    val plan = LearningEngine.planUsageDeduction(
        requestedMs = milliseconds,
        availableMs = capacity(config),
        freeQuotaMs = freeQuotaNow,
        bootQuotaMs = bootQuotaNow,
        creditMs = creditNow
    )
    if (plan.totalConsumed <= 0L) { flushUsage(); return }
    val db = store.writableDatabase
    db.beginTransaction()
    try {
        if (plan.freeQuotaConsumed > 0L) {
            put("free_quota_ms", (freeQuotaNow - plan.freeQuotaConsumed).coerceAtLeast(0L))
            put("free_used_ms", number("free_used_ms") + plan.freeQuotaConsumed)
        }
        if (plan.bootQuotaConsumed > 0L) {
            put("boot_quota_ms", (bootQuotaNow - plan.bootQuotaConsumed).coerceAtLeast(0L))
        }
        if (plan.creditConsumed > 0L) {
            put("credit_ms", (creditNow - plan.creditConsumed).coerceAtLeast(0L))
        }
        put("used_ms", number("used_ms") + plan.totalConsumed)
        pendingUsageMs += plan.totalConsumed
        time.consumed(config, plan.totalConsumed)
        val quotaExhausted = (plan.freeQuotaConsumed > 0L && freeQuotaNow <= plan.freeQuotaConsumed) ||
            (plan.bootQuotaConsumed > 0L && bootQuotaNow <= plan.bootQuotaConsumed) ||
            (plan.creditConsumed > 0L && creditNow <= plan.creditConsumed)
        if (pendingUsageMs >= 30_000L || quotaExhausted) flushUsage()
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

internal fun LearningEngine.consumeRecoveredImpl(milliseconds: Long) {
    try {
        var left = milliseconds.coerceAtLeast(0L)
        while (left > 0L) {
            val chunk = minOf(left, LearningEngine.MAX_SINGLE_CONSUME_MS)
            consumeUsage(chunk)
            left -= chunk
        }
    } finally {
        publishNavigation()
    }
}

internal fun LearningEngine.flushUsageImpl() {
    if (pendingUsageMs <= 0) return
    store.logEvent("usage", JSONObject().put("milliseconds", pendingUsageMs))
    pendingUsageMs = 0
}
