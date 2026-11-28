package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** 按 IANA 时区 ID 解析时区；留空或非法值回退设备默认时区。 */
internal fun resolveTimeZoneImpl(rawZoneId: String): TimeZone {
    val id = rawZoneId.trim()
    if (id.isEmpty()) return TimeZone.getDefault()
    val zone = TimeZone.getTimeZone(id)
    return if (zone.id == "GMT" && id != "GMT" && id != "UTC") TimeZone.getDefault() else zone
}

/** 计算指定时区在给定时刻所在自然日的 00:00:00.000 毫秒时间戳。 */
internal fun startOfDayMillisImpl(nowMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
    timeInMillis = nowMs
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun LocalStore.logEventImpl(type: String, payload: JSONObject) {
    // 事件按当前孩子记账：历史、使用时长、完成组数都只统计这个孩子自己的
    val child = activeChild()
    val db = writableDatabase
    val isRound = type == "quiz_pass"
    val ownTransaction = isRound && !db.inTransaction()
    if (ownTransaction) db.beginTransaction()
    try {
        // 组数随额度结算。时钟有偏差时，事件时间仍用于历史展示。
        val rounds = if (isRound && rawGet(scoped("usage_day", child)).isNotEmpty()) roundsDoneTodayFor(child) + 1 else null
        db.execSQL("INSERT INTO events(type,payload,ts,child) VALUES(?,?,?,?)",
            arrayOf<Any>(type, payload.toString(), clockReading().millis, child))
        if (rounds != null) rawSet(scoped("usage_day_rounds", child), rounds.toString())
        if (ownTransaction) db.setTransactionSuccessful()
    } finally {
        if (ownTransaction) db.endTransaction()
    }
}

internal fun LocalStore.recentEventsImpl(limit: Int): JSONArray {
    val out = JSONArray()
    readableDatabase.rawQuery("SELECT type,payload,ts FROM events WHERE type!='usage' AND child=? ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}",
        arrayOf(activeChild())).use { c ->
        while (c.moveToNext()) out.put(JSONObject().put("type", c.getString(0)).put("payload", JSONObject(c.getString(1))).put("ts", c.getLong(2)))
    }
    return out
}

internal fun LocalStore.statsFor(start: Long, end: Long, child: String): JSONObject {
    var usedMs = 0L
    var answered = 0
    var correct = 0
    var rounds = 0
    readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<? AND child=?",
        arrayOf(start.toString(), end.toString(), child)).use { c ->
        while (c.moveToNext()) {
            val p = try { JSONObject(c.getString(1)) } catch (_: Exception) { JSONObject() }
            when (c.getString(0)) {
                "usage" -> usedMs += p.optLong("milliseconds")
                "answer", "flashcard" -> { answered++; if (p.optBoolean("correct")) correct++ }
                "quiz" -> if (!p.optBoolean("native")) { answered += p.optInt("total"); correct += p.optInt("correct") }
                "quiz_pass" -> rounds++
            }
        }
    }
    return JSONObject().put("usedMinutes", usedMs / 60000.0).put("watch", usedMs / 60000.0)
        .put("answered", answered).put("correct", correct).put("rounds", rounds)
        .put("accuracy", if (answered > 0) correct * 100 / answered else 0)
}

internal fun LocalStore.todayStatsImpl(): JSONObject {
    val child = activeChild()
    val start = startOfToday(child)
    return statsFor(start, nextDayStart(start, child), child).put("rounds", roundsDoneTodayFor(child))
}

internal fun LocalStore.roundsDoneTodayFor(child: String): Int {
    val usageDay = rawGet(scoped("usage_day", child)).toLongOrNull()
        ?: return roundsInDay(startOfToday(child), child)
    // 旧版尚未存储组数时，从当天的事件恢复。
    return rawGet(scoped("usage_day_rounds", child)).toIntOrNull() ?: roundsInDay(usageDay, child)
}

internal fun LocalStore.roundsInDayImpl(dayStart: Long, child: String): Int = readableDatabase.rawQuery(
    "SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=? AND ts<? AND child=?",
    arrayOf(dayStart.toString(), nextDayStart(dayStart, child).toString(), child)
).use { if (it.moveToFirst()) it.getInt(0) else 0 }

internal fun LocalStore.historyDaysImpl(days: Int): JSONArray {
    val out = JSONArray()
    val child = activeChild()
    val today = calendarFor(child).atStartOfDay()
    for (d in 0 until days.coerceIn(1, 90)) {
        val cal = today.clone() as Calendar
        cal.add(Calendar.DAY_OF_YEAR, -d)
        val start = cal.atStartOfDay().timeInMillis
        val label = if (d == 0) "今天" else if (d == 1) "昨天" else "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
        cal.add(Calendar.DAY_OF_YEAR, 1)
        out.put(statsFor(start, cal.atStartOfDay().timeInMillis, child).put("label", label))
    }
    return out
}

internal fun LocalStore.timeZoneForImpl(child: String): TimeZone =
    LocalStore.resolveTimeZone(try { config(child).optString("timezone") } catch (_: Exception) { "" })

internal fun LocalStore.calendarForImpl(child: String): Calendar = Calendar.getInstance(timeZoneFor(child)).apply {
    timeInMillis = clockReading().millis
}

private fun Calendar.atStartOfDay(): Calendar = apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

internal fun LocalStore.nextDayStart(start: Long, child: String): Long = Calendar.getInstance(timeZoneFor(child)).apply {
    timeInMillis = start
    add(Calendar.DAY_OF_YEAR, 1)
}.atStartOfDay().timeInMillis
