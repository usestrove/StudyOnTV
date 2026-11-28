package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONObject

internal fun LearningEngine.overrideImpl(): JSONObject? {
    val raw = store.get("remote_override").takeIf { it.isNotEmpty() } ?: return null
    val o = try { JSONObject(raw) } catch (_: Exception) { store.set("remote_override", ""); return null }
    val until = o.optLong("until")
    val mode = o.optString("mode")
    // 持久化到本次开机：应用重建、待机不失效；断电后即使没收到关机广播也会恢复规则。
    if (o.optBoolean("untilShutdown")) {
        val boot = store.bootCount()
        if (mode != "unlock" || boot < 0 || o.optInt("boot", -1) != boot ||
            o.optLong("atElapsed", -1L) !in 0..SystemClock.elapsedRealtime()) {
            store.set("remote_override", "")
            store.logEvent("remote", JSONObject().put("action", "expired").put("mode", mode).put("untilShutdown", true))
            return null
        }
        return o.put("remainingSeconds", -1)
    }
    if (mode == "indefinite") return o.put("remainingSeconds", -1)
    if (until == 0L) return o.put("remainingSeconds", -1)
    val left = if (o.optInt("boot", -2) == store.bootCount()) o.optLong("untilElapsed") - SystemClock.elapsedRealtime()
        else until - System.currentTimeMillis()
    if (left <= 0) {
        store.set("remote_override", "")
        store.logEvent("remote", JSONObject().put("action", "expired").put("mode", o.optString("mode")))
        return null
    }
    return o.put("remainingSeconds", (left + 999) / 1000)
}

internal fun LearningEngine.remoteImpl(action: String, minutes: Int, source: String, untilShutdown: Boolean): JSONObject {
    require(!untilShutdown || action == "unlock") { "只有临时解锁支持到本次关机" }
    require(!untilShutdown || minutes == 0) { "到本次关机不能同时指定解锁分钟数" }
    when (action) {
        "credit" -> { day(); time.adjust(minutes, store.config()) }
        "cancel" -> store.set("remote_override", "")
        "indefinite" -> {
            store.set("remote_override", JSONObject().put("mode", "indefinite").put("minutes", 0)
                .put("at", System.currentTimeMillis()).put("source", source).put("boot", store.bootCount())
                .put("until", 0L).put("untilElapsed", 0L).toString())
        }
        "unlock", "lock" -> {
            require(action == "lock" || untilShutdown || minutes in 1..24 * 60) { "解锁时长需要 1 分钟到 24 小时" }
            require(minutes in 0..7 * 24 * 60) { "锁定时长不正确" }
            val boot = store.bootCount()
            require(!untilShutdown || boot >= 0) { "当前设备无法可靠识别开机状态，请使用定时解锁" }
            val ms = minutes * 60000L
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            store.set("remote_override", JSONObject().put("mode", action).put("minutes", minutes)
                .put("at", wall).put("source", source).put("boot", boot)
                .put("untilShutdown", untilShutdown).put("atElapsed", elapsed)
                // 防止旧版把本次开机解锁误读为无期限。
                .put("until", if (untilShutdown) -1L else if (ms == 0L) 0L else wall + ms)
                .put("untilElapsed", if (untilShutdown) -1L else if (ms == 0L) 0L else elapsed + ms).toString())
        }
        "clear_extra" -> {
            val cleared = clearExtraPool().optLong("cleared", 0L)
            store.logEvent("remote", JSONObject().put("action", action).put("cleared", cleared).put("source", source))
            return status()
        }
        else -> throw IllegalArgumentException("未知操作")
    }
    flushUsage()
    store.logEvent("remote", JSONObject().put("action", action).put("minutes", minutes).put("source", source).put("untilShutdown", untilShutdown))
    return status()
}
