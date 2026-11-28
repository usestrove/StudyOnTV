package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun LocalStore.configImpl(child: String): JSONObject {
    // 每次返回全新 JSONObject 副本，防止调用方原地修改返回对象污染缓存
    configCache[child]?.let { return JSONObject(it) }
    val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
        .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
        .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true)
        .put("supervisionEnabled", true)
        .put("spacedRepetitionEnabled", false).put("memorizeFirst", false).put("spacedScope", "all")
        // 覆盖优先：没答对过的题先出，保证题库里的题都会被走到一次（家长面板可关）
        .put("coverageFirst", true)
        // 新题优先：一次都没出过的题排在前面（#230，默认关）
        .put("newFirst", false)
        .put("playMode", "round").put("extraEnabled", true).put("shuffleOptions", false)
        .put("showCountdown", true).put("countdownPosition", "top-right")
        .put("wrongThreshold", 3).put("extraQuestions", 2)
        .put("difficultyRewardEnabled", false)
        .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
        .put("praisePhrases", JSONArray(LocalStore.DEFAULT_PRAISE)).put("encouragePhrases", JSONArray(LocalStore.DEFAULT_ENCOURAGE))
        .put("autoNextSeconds", 0).put("scheduleMode", "allow").put("blockSettingsDuringControl", true).put("theme", if (BuildConfig.FLAVOR == "tv") "dark" else "system")
        .put("holidayMode", "sunday")
        .put("bootFreeMinutes", 0).put("bootFreeIntervalMinutes", 0)
        .put("dailyLimitEnabled", true).put("roundsLimitEnabled", true)
        .put("restEnabled", false).put("restWatchMinutes", 40).put("restMinutes", 10)
        .put("wrongReviewSeconds", 0).put("fontScale", 100).put("childSelectLevel", false)
        .put("trafficAssistEnabled", false).put("backgroundAnchorEnabled", true)
        .put("forceFullscreenGate", false)
        .put("watchWhitelist", JSONArray()).put("allowQuizUnlock", false)
        .put("learningContent", "quiz").put("educationalVideos", JSONArray()).put("videoMinutes", 1)
        .put("quizBanks", JSONArray())
        .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
        .put("gradeSubjects", JSONObject())
        .put("timePoints", JSONArray()).put("exemptWindows", JSONArray())
        .put("timezone", "")
        .put("version", 1)
    val saved = try { JSONObject(rawGet(scoped("config", child), "{}")) } catch (_: Exception) { JSONObject() }
    saved.keys().forEach { defaults.put(it, saved.get(it)) }
    defaults.put("theme", when (defaults.optString("theme")) { "light", "daylight" -> "light"; "system" -> "system"; "dark", "midnight", "nord", "amber" -> "dark"; else -> if (BuildConfig.FLAVOR == "tv") "dark" else "system" })
    val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    val validStart = timePattern.matches(defaults.optString("windowStart"))
    val validEnd = timePattern.matches(defaults.optString("windowEnd"))
    if (!validStart) defaults.put("windowStart", "18:30")
    if (!validEnd) defaults.put("windowEnd", "20:00")
    if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
    val migrated = normalizeWeek(saved.optJSONArray("weekSlots"))
        ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
            val oldDefault = !defaults.optBoolean("allDay") &&
                defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
            if (oldDefault) { defaults.put("allDay", true); fullWeek() }
            else weekFromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
        } else fullWeek()
    defaults.put("weekSlots", migrated)
    if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) {
        defaults.put("holidayMode", "sunday")
    }
    if (defaults.optString("spacedScope") !in listOf("all", "wrong_only")) {
        defaults.put("spacedScope", "all")
    }
    defaults.put("countdownPosition", CountdownOverlay.normalizePosition(defaults.optString("countdownPosition")))
    defaults.put("gradeSubjects", LocalStore.normalizeGradeSubjects(defaults.optJSONObject("gradeSubjects")))
    val snapshot = defaults.toString()
    configCache[child] = snapshot
    return JSONObject(snapshot)
}

private fun weekFromWindow(allDay: Boolean, start: String, end: String): JSONArray {
    fun minutes(value: String): Int? = try { value.split(":").let { it[0].toInt() * 60 + it[1].toInt() } } catch (_: Exception) { null }
    val from = minutes(start) ?: 18 * 60 + 30
    val to = minutes(end) ?: 20 * 60
    val chars = CharArray(48) { index ->
        val minute = index * 30
        val on = allDay || if (from < to) minute in from until to else minute >= from || minute < to
        if (on) '1' else '0'
    }
    val day = String(chars)
    return JSONArray().apply { repeat(7) { put(day) } }
}

private fun fullWeek(): JSONArray = JSONArray().apply { repeat(7) { put("1".repeat(48)) } }

private fun normalizeWeek(raw: JSONArray?): JSONArray? {
    if (raw == null || raw.length() != 7) return null
    val out = JSONArray()
    for (i in 0 until 7) {
        val day = raw.optString(i)
        if (day.length != 48 || day.any { it != '0' && it != '1' }) return null
        out.put(day)
    }
    return out
}

/**
 * 网课时段 / 大人豁免的校验（Issue #112）。时间精确到分钟，end < start 表示跨午夜。
 * days 用真实星期几：0 = 周一 … 6 = 周日，不受 holidayMode 影响。
 */
private fun normalizeWindows(raw: JSONArray?, counted: Boolean, label: String): JSONArray {
    val out = JSONArray()
    val source = raw ?: return out
    require(source.length() <= 50) { "${label}最多 50 条" }
    val pattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    for (i in 0 until source.length()) {
        val item = source.optJSONObject(i) ?: throw IllegalArgumentException("${label}格式不正确")
        val name = item.optString("name").trim()
        require(name.isNotEmpty() && name.length <= 20) { "${label}名称需要 1–20 个字" }
        val start = item.optString("start")
        val end = item.optString("end")
        require(pattern.matches(start) && pattern.matches(end)) { "${label}需要有效的开始和结束时间（精确到分钟）" }
        require(start != end) { "${label}的开始与结束时间不能相同" }
        val days = JSONArray()
        val seen = mutableSetOf<Int>()
        val rawDays = item.optJSONArray("days") ?: JSONArray()
        for (d in 0 until rawDays.length()) {
            val day = rawDays.optInt(d, -1)
            require(day in 0..6) { "${label}的星期设置不正确" }
            if (seen.add(day)) days.put(day)
        }
        require(days.length() > 0) { "请为${label}选择星期" }
        val clean = JSONObject()
            .put("id", item.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= 64 }
                ?: ((if (counted) "tp_" else "ex_") + UUID.randomUUID()))
            .put("name", name).put("days", days).put("start", start).put("end", end)
        if (counted) {
            val mode = item.optString("mode", "free")
            require(mode in listOf("free", "counted")) { "${label}的用途需要为自由使用或计入限额" }
            clean.put("mode", mode)
            if (mode == "counted") {
                val minutes = item.optInt("dailyMinutes", 0)
                require(minutes in 1..480) { "计入限额的${label}需要 1–480 分钟" }
                clean.put("dailyMinutes", minutes)
            }
        }
        out.put(clean)
    }
    return out
}

internal fun LocalStore.saveConfigImpl(input: JSONObject): JSONObject {
    val next = config()
    val ranges = mapOf("dailyMinutes" to 1..480, "dailyFreeMinutes" to 0..120, "sessionMinutes" to 1..120,
        "questionsPerRound" to 1..100, "roundsPerDay" to 1..100,
        "wrongThreshold" to 1..100, "extraQuestions" to 1..100,
        "bootFreeMinutes" to 0..120, "bootFreeIntervalMinutes" to 0..1440,
        "restWatchMinutes" to 1..480, "restMinutes" to 1..120,
        "wrongReviewSeconds" to 0..300, "fontScale" to 75..200, "videoMinutes" to 1..30)
    ranges.forEach { (key, range) ->
        if (input.has(key)) {
            val value = input.getDouble(key)
            require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "$key 超出范围" }
            next.put(key, value.toInt())
        }
    }
    val wantsAllDay = if (input.has("allDay")) input.getBoolean("allDay") else next.optBoolean("allDay")
    listOf("windowStart", "windowEnd").forEach { key ->
        if (input.has(key)) {
            val valid = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(input.optString(key))
            require(wantsAllDay || valid) { "请输入有效的开始和结束时间" }
            if (valid) next.put(key, input.getString(key))
        }
    }
    if (input.has("timezone")) {
        val zone = input.optString("timezone").trim()
        require(zone in DeviceClock.ZONES) { "不支持的时区" }
        next.put("timezone", zone)
    }
    if (input.has("passRatio")) {
        val ratio = input.getDouble("passRatio")
        require(ratio.isFinite() && ratio > 0 && ratio <= 1) { "答对比例需要大于 0 且不超过 100%" }
        next.put("passRatio", ratio)
    }
    if (input.has("learningContent")) {
        require(input.getString("learningContent") in listOf("quiz", "video", "alternate")) { "未知学习内容模式" }
        next.put("learningContent", input.getString("learningContent"))
    }
    if (input.has("educationalVideos")) {
        val urls = input.getJSONArray("educationalVideos")
        require(urls.length() <= 50) { "最多 50 个公益视频" }
        for (i in 0 until urls.length()) {
            val url = urls.getString(i)
            require(url.length <= 2048 && (url.startsWith("https://") || url.startsWith("http://"))) { "请使用 HTTP/HTTPS 视频直链" }
            if (LocalOnlyGuard.enabled) {
                require(LocalOnlyGuard.isLocalUrl(url)) { "仅本地隐私版（EU Local-Only）仅允许配置本机或局域网内的公益视频" }
            }
        }
        next.put("educationalVideos", urls)
    }
    if (input.has("watchWhitelist")) {
        val raw = input.optJSONArray("watchWhitelist") ?: throw IllegalArgumentException("白名单格式不正确")
        val clean = JSONArray()
        WatchWhitelist.normalize((0 until raw.length()).map { raw.optString(it) }).forEach { clean.put(it) }
        next.put("watchWhitelist", clean)
    }
    require(next.optString("learningContent", "quiz") == "quiz" || next.getJSONArray("educationalVideos").length() > 0) { "视频学习模式需要添加视频地址" }
    if (input.has("playMode")) {
        require(input.getString("playMode") in listOf("round", "streak", "practice", "count")) { "未知玩法" }
        next.put("playMode", input.getString("playMode"))
    }
    if (input.has("spacedScope")) {
        val scope = input.optString("spacedScope")
        require(scope in listOf("all", "wrong_only")) { "未知复习范围" }
        next.put("spacedScope", scope)
    }
    if (input.has("countdownPosition")) {
        val pos = input.optString("countdownPosition").trim()
        require(pos in CountdownOverlay.VALID_POSITIONS) { "未知的倒计时显示位置" }
        next.put("countdownPosition", pos)
    }
    listOf(
        "supervisionEnabled", "memorizeFirst", "extraEnabled", "coverageFirst", "newFirst",
        "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl",
        "shuffleOptions", "showCountdown", "dailyLimitEnabled", "roundsLimitEnabled", "restEnabled",
        "childSelectLevel", "trafficAssistEnabled", "backgroundAnchorEnabled", "forceFullscreenGate",
        "wrongFirst", "allowQuizUnlock"
    ).forEach {
        if (input.has(it)) next.put(it, input.getBoolean(it))
    }
    for (key in listOf("praisePhrases", "encouragePhrases")) if (input.has(key)) {
        val raw = input.getJSONArray(key)
        val clean = JSONArray()
        for (i in 0 until raw.length()) raw.optString(i).trim().takeIf { it.isNotEmpty() }?.let {
            require(it.length <= 40) { "每句鼓励语不超过 40 个字" }
            clean.put(it)
        }
        require(clean.length() <= 30) { "鼓励语最多 30 句" }
        next.put(key, if (clean.length() == 0) JSONArray(if (key == "praisePhrases") LocalStore.DEFAULT_PRAISE else LocalStore.DEFAULT_ENCOURAGE) else clean)
    }
    if (input.has("scheduleMode")) {
        require(input.getString("scheduleMode") in listOf("allow", "control")) { "未知的时段用途" }
        next.put("scheduleMode", input.getString("scheduleMode"))
    }
    for (key in listOf("quizSubjects", "quizLevels", "quizBanks")) if (input.has(key)) {
        val raw = input.optJSONArray(key) ?: JSONArray()
        val clean = JSONArray()
        for (i in 0 until minOf(raw.length(), 50)) raw.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= (if (key.endsWith("Banks")) 64 else 32) }?.let { value ->
            if (!(0 until clean.length()).any { clean.optString(it) == value }) clean.put(value)
        }
        next.put(key, clean)
    }
    if (input.has("gradeSubjects")) {
        next.put("gradeSubjects", LocalStore.normalizeGradeSubjects(input.optJSONObject("gradeSubjects")))
    }
    if (input.has("weekSlots")) {
        val clean = normalizeWeek(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("可用时段格式不正确")
        next.put("weekSlots", clean)
    }
    if (input.has("timePoints")) next.put("timePoints", normalizeWindows(input.optJSONArray("timePoints"), true, "网课时段"))
    if (input.has("exemptWindows")) next.put("exemptWindows", normalizeWindows(input.optJSONArray("exemptWindows"), false, "大人豁免时段"))
    if (input.has("holidayMode")) {
        require(input.getString("holidayMode") in listOf("sunday", "normal")) { "未知的节假日规则" }
        next.put("holidayMode", input.getString("holidayMode"))
    }
    if (input.has("theme")) {
        require(input.getString("theme") in listOf("dark", "light", "system")) { "外观需要为深色、浅色或跟随系统" }
        next.put("theme", input.getString("theme"))
    }
    if (input.has("autoNextSeconds")) {
        val value = input.getInt("autoNextSeconds")
        require(value in 0..30) { "自动下一题需要为 0–30 秒，0 表示手动" }
        next.put("autoNextSeconds", value)
    }
    if (input.has("difficultyRewards")) {
        val rewards = input.getJSONObject("difficultyRewards")
        for (star in 1..5) {
            val minutes = rewards.getDouble(star.toString())
            require(minutes.isFinite() && minutes in 0.1..120.0) { "每题奖励需要为 0.1–120 分钟" }
        }
        next.put("difficultyRewards", rewards)
    }
    next.put("version", next.optInt("version", 1) + 1)
    val snapshot = next.toString()
    set("config", snapshot)
    configCache[activeChild()] = snapshot
    return JSONObject(snapshot)
}
