package com.studyontv.tv

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 网课时段 / 大人豁免的命中判断（Issue #112）。
 *
 * 与 [LearningEngine] 内部的 `dayIndex()` 不同：这里用**真实星期几**，不把法定节假日折算成周日，
 * 因为「周三 19:00 的网课」在节假日也仍然是周三；0 = 周一 … 6 = 周日。
 *
 * 时间精确到分钟，区间左闭右开（19:00–20:15 含 19:00 与 20:14，不含 20:15）。
 * end < start 表示跨午夜（23:30–00:30），午夜之后的部分算作**前一天**的安排。
 */
object TimePointsMath {
    private val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    /** "HH:mm" → 当天第几分钟；格式不对返回 null。 */
    fun minutes(value: String?): Int? {
        val text = value ?: return null
        if (!pattern.matches(text)) return null
        return text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()
    }

    fun label(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** [minute] 是否落在 [start, end) 内；start == end 视为空窗口。 */
    fun inRange(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }

    /** [days] 里的某一天在 [minute] 是否命中 [start, end)；跨午夜时延续到次日的部分属于前一天。 */
    fun hits(days: Set<Int>, start: Int, end: Int, day: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> day in days && inRange(start, end, minute)
        else -> (day in days && minute >= start) || ((day + 6) % 7 in days && minute < end)
    }
}

/** 一个命中的网课时段或大人豁免窗口。 */
internal class ScheduleWindow(
    val name: String,
    val days: Set<Int>,
    val start: Int,
    val end: Int,
    val mode: String,
    val dailyMinutes: Int,
    val until: String
)

/** 在 9 天窗口内查找下一个开放半小时槽；若周计划全禁（无任何 '1'）返回 0L，避免误报当前时刻或明日零点。 */
internal fun findNextAvailableSlotMillisImpl(
    slots: List<String>,
    nowMs: Long,
    tomorrow: Boolean,
    timeZone: TimeZone,
    dayIndexOf: (Calendar) -> Int
): Long {
    val base = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMs
        if (tomorrow) {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    for (offset in 0..8) {
        val day = (base.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val index = dayIndexOf(day)
        val daySlots = slots.getOrNull(index) ?: continue
        for (slot in 0 until minOf(48, daySlots.length)) {
            if (daySlots[slot] != '1') continue
            val candidate = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, slot / 2); set(Calendar.MINUTE, (slot % 2) * 30)
            }.timeInMillis
            if (candidate > nowMs) return candidate
        }
    }
    return 0L
}

internal fun LearningEngine.windows(config: JSONObject, key: String, defaultMode: String): List<ScheduleWindow> {
    val raw = config.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<ScheduleWindow>(raw.length())
    for (i in 0 until raw.length()) {
        val item = raw.optJSONObject(i) ?: continue
        val start = TimePointsMath.minutes(item.optString("start")) ?: continue
        val end = TimePointsMath.minutes(item.optString("end")) ?: continue
        if (start == end) continue
        val days = LinkedHashSet<Int>()
        item.optJSONArray("days")?.let { arr ->
            for (d in 0 until arr.length()) arr.optInt(d, -1).takeIf { it in 0..6 }?.let { days.add(it) }
        }
        if (days.isEmpty()) continue
        val mode = if (defaultMode == "exempt") "exempt" else if (item.optString("mode") == "counted") "counted" else "free"
        val minutes = item.optInt("dailyMinutes", 0)
        if (mode == "counted" && minutes !in 1..480) continue
        out.add(ScheduleWindow(item.optString("name").take(20), days, start, end, mode, if (mode == "counted") minutes else 0, TimePointsMath.label(end)))
    }
    return out
}

internal fun LearningEngine.resolveWindow(config: JSONObject, cal: Calendar): ScheduleWindow? {
    val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    for (w in windows(config, "exemptWindows", "exempt")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
    for (w in windows(config, "timePoints", "free")) if (TimePointsMath.hits(w.days, w.start, w.end, day, minute)) return w
    return null
}

internal fun LearningEngine.activeWindow(config: JSONObject): ScheduleWindow? = resolveWindow(config, now())

internal fun LearningEngine.limitConfig(config: JSONObject): JSONObject {
    val point = activeWindow(config) ?: return config
    if (point.mode != "counted") return config
    return JSONObject(config.toString()).put("dailyMinutes", point.dailyMinutes)
}

internal fun LearningEngine.weekSlots(config: JSONObject): List<String>? {
    val raw = config.optJSONArray("weekSlots") ?: return null
    if (raw.length() != 7) return null
    val list = (0 until 7).map { raw.optString(it) }
    return list.takeIf { days -> days.all { it.length == 48 && it.all { ch -> ch == '0' || ch == '1' } } }
}

/** 周一 = 0 … 周日 = 6；法定节假日可以按周日安排 */
internal fun LearningEngine.dayIndex(config: JSONObject, cal: Calendar): Int {
    if (config.optString("holidayMode", "sunday") == "sunday") {
        val holiday = try { HolidayService.isHoliday(DeviceRuntime.appContext, cal) } catch (_: Throwable) { false }
        if (holiday) return 6
    }
    return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
}

internal fun LearningEngine.allowedAt(config: JSONObject, cal: Calendar): Boolean {
    if (config.optBoolean("allDay")) return true
    val slots = weekSlots(config) ?: return true
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return slots[dayIndex(config, cal)].getOrNull(minute / 30) == '1'
}

internal fun LearningEngine.inWindow(config: JSONObject): Boolean = allowedAt(config, now())

internal fun LearningEngine.scheduleText(config: JSONObject): String {
    if (config.optBoolean("allDay")) return "全天可用"
    val slots = weekSlots(config) ?: return ""
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val parts = mutableListOf<String>()
    var start = 0
    while (start < 7) {
        var end = start
        while (end + 1 < 7 && slots[end + 1] == slots[start]) end++
        val label = when (end - start) {
            0 -> labels[start]
            1 -> labels[start] + "、" + labels[end]
            else -> labels[start] + "至" + labels[end]
        }
        val ranges = slotRanges(slots[start])
        parts += if (ranges.isEmpty()) "$label 不可用" else label + " " + ranges.joinToString("、")
        start = end + 1
    }
    val text = parts.joinToString("，")
    return if (text.length <= 80) text else text.take(78) + "…"
}

internal fun LearningEngine.slotRanges(day: String): List<String> {
    fun clock(slot: Int) = "%02d:%02d".format(slot / 2, (slot % 2) * 30)
    val out = mutableListOf<String>()
    var i = 0
    while (i < 48) {
        if (day[i] != '1') { i++; continue }
        var j = i
        while (j + 1 < 48 && day[j + 1] == '1') j++
        out += clock(i) + "–" + clock(j + 1)
        i = j + 1
    }
    return out
}

internal fun LearningEngine.nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
    val currentCal = now()
    val nowMs = currentCal.timeInMillis
    val base = (currentCal.clone() as Calendar).apply {
        if (tomorrow) {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (config.optBoolean("allDay")) return base.timeInMillis
    val slots = weekSlots(config) ?: return base.timeInMillis
    return LearningEngine.findNextAvailableSlotMillis(slots, nowMs, tomorrow, currentCal.timeZone) { dayIndex(config, it) }
}

internal fun LearningEngine.scheduleFree(c: JSONObject): Boolean =
    c.optString("scheduleMode", "allow") == "control" && !c.optBoolean("allDay") && !inWindow(c)
