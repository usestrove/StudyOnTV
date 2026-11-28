package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 连续学习天数：截至今天（今天尚未学习时延续昨天）连续每天至少有 1 条作答或闪卡背诵记录。 */
internal fun LocalStore.computeStreakDays(child: String): Int {
    val todayStart = startOfToday(child)
    val cutoff = todayStart - 366L * 86_400_000L
    val activeDays = HashSet<Int>()
    val cal = calendarFor(child)
    readableDatabase.rawQuery(
        "SELECT ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
        arrayOf(child, cutoff.toString())
    ).use { c ->
        while (c.moveToNext()) {
            cal.timeInMillis = c.getLong(0)
            val key = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
            activeDays.add(key)
        }
    }
    if (activeDays.isEmpty()) return 0
    val cursor = calendarFor(child).apply { timeInMillis = todayStart }
    fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    if (dayKey(cursor) !in activeDays) {
        cursor.add(Calendar.DAY_OF_YEAR, -1)
        if (dayKey(cursor) !in activeDays) return 0
    }
    var streak = 0
    while (dayKey(cursor) in activeDays) {
        streak++
        cursor.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
}

/** 学习主页摘要卡片（连胜天数、今日题量、待复习、错题数、已掌握题数）。 */
internal fun LocalStore.studySummaryImpl(child: String): JSONObject {
    val nowMs = clockReading().millis
    val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
    var totalQuestions = 0
    var dueCount = 0
    var wrongCount = 0
    var masteredCount = 0
    readableDatabase.rawQuery(
        "SELECT COALESCE(stats.last_correct,-1),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
            "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
            "FROM questions " +
            "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
            "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
            "WHERE questions.enabled=1",
        arrayOf(child, child)
    ).use { c ->
        while (c.moveToNext()) {
            totalQuestions++
            val lastCorrect = c.getInt(0)
            val answered = c.getInt(1)
            val correct = c.getInt(2)
            val hasProgress = c.getInt(3) != 0
            val intervalDays = c.getLong(4)
            val dueAt = c.getLong(5)
            if (lastCorrect == 0) wrongCount++
            if (hasProgress && intervalDays >= 21) masteredCount++
            if (hasProgress && dueAt <= nowMs && LocalStore.inSpacedScope(wrongOnly, lastCorrect, answered, correct)) dueCount++
        }
    }
    var todayAnswered = 0
    var todayCorrect = 0
    readableDatabase.rawQuery(
        "SELECT payload FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard')",
        arrayOf(child, startOfToday(child).toString())
    ).use { c ->
        while (c.moveToNext()) {
            todayAnswered++
            val p = try { JSONObject(c.getString(0)) } catch (_: Exception) { JSONObject() }
            if (p.optBoolean("correct")) todayCorrect++
        }
    }
    return JSONObject()
        .put("streakDays", computeStreakDays(child))
        .put("todayAnswered", todayAnswered)
        .put("todayCorrect", todayCorrect)
        .put("dueCount", dueCount)
        .put("wrongCount", wrongCount)
        .put("masteredCount", masteredCount)
        .put("totalQuestions", totalQuestions)
}

/** 家长端完整学情分析：记忆成熟度分布、学科/题库掌握率、近 N 日趋势、高频错题与未来 7 天复习预测。 */
internal fun LocalStore.learningAnalyticsImpl(days: Int, child: String): JSONObject {
    val windowDays = days.coerceIn(7, 60)
    val nowMs = clockReading().millis
    val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
    val childZone = timeZoneFor(child)
    val todayStart = LocalStore.startOfDayMillis(nowMs, childZone)

    class SubjectStat(val subject: String) {
        var total = 0; var answered = 0; var correct = 0; var wrong = 0; var mastered = 0
        val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
    }
    class BankStat(val bank: String) {
        var total = 0; var covered = 0; var answered = 0; var correct = 0; var wrong = 0
        val accuracy: Int get() = if (answered > 0) Math.round(correct * 100f / answered) else 0
    }

    val subjects = LinkedHashMap<String, SubjectStat>()
    val banks = LinkedHashMap<String, BankStat>()
    val hardCandidates = ArrayList<Pair<Long, JSONObject>>()

    // 未来 7 天每天的起止时刻（按该孩子配置的时区切分日界线）
    val forecastBounds = Array(7) { offset ->
        val dayStart = Calendar.getInstance(childZone).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, offset)
        }
        val start = dayStart.timeInMillis
        val label = when (offset) {
            0 -> "今天"
            1 -> "明天"
            else -> "${dayStart.get(Calendar.MONTH) + 1}/${dayStart.get(Calendar.DAY_OF_MONTH)}"
        }
        dayStart.add(Calendar.DAY_OF_YEAR, 1)
        Triple(start, dayStart.timeInMillis, label)
    }
    val forecastCounts = IntArray(7)

    var totalQuestions = 0
    var unseen = 0
    var learning = 0
    var young = 0
    var mature = 0
    var studiedQuestions = 0
    var wrongCount = 0
    var dueNow = 0
    var totalAnswered = 0
    var totalCorrect = 0

    readableDatabase.rawQuery(
        "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank,questions.type,questions.tip," +
            "COALESCE(stats.presented,0),COALESCE(stats.answered,0),COALESCE(stats.correct,0)," +
            "COALESCE(stats.last_at,0),COALESCE(stats.last_correct,-1)," +
            "progress.qid IS NOT NULL,COALESCE(progress.interval_days,0),COALESCE(progress.due_at,0) " +
            "FROM questions " +
            "LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
            "LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=? " +
            "WHERE questions.enabled=1",
        arrayOf(child, child)
    ).use { c ->
        while (c.moveToNext()) {
            totalQuestions++
            val qid = c.getString(0) ?: ""
            val stem = c.getString(1) ?: ""
            val rawSubject = (c.getString(2) ?: "").trim()
            val subjectKey = rawSubject.ifEmpty { "未分类" }
            val level = c.getString(3) ?: ""
            val bankKey = (c.getString(4) ?: "").trim().ifEmpty { "默认题库" }
            val type = (c.getString(5) ?: "").ifBlank { "single" }
            val tip = c.getString(6) ?: ""
            val presented = c.getInt(7)
            val answered = c.getInt(8)
            val correct = c.getInt(9)
            val lastAt = c.getLong(10)
            val lastCorrect = c.getInt(11)
            val hasProgress = c.getInt(12) != 0
            val intervalDays = c.getLong(13)
            val dueAt = c.getLong(14)

            totalAnswered += answered
            totalCorrect += correct
            if (lastCorrect == 0) wrongCount++

            val isMastered = hasProgress && intervalDays >= 21
            if (!hasProgress) {
                unseen++
            } else {
                when {
                    intervalDays <= 1L -> learning++
                    intervalDays <= 20L -> young++
                    else -> mature++
                }
                if (LocalStore.inSpacedScope(wrongOnly, lastCorrect, answered, correct)) {
                    if (dueAt <= nowMs) dueNow++
                    for (d in 0 until 7) {
                        val (fStart, fEnd, _) = forecastBounds[d]
                        val hit = if (d == 0) dueAt < fEnd else dueAt in fStart until fEnd
                        if (hit) { forecastCounts[d]++; break }
                    }
                }
            }
            if (hasProgress || answered > 0) studiedQuestions++

            subjects.getOrPut(subjectKey) { SubjectStat(subjectKey) }.apply {
                total++
                this.answered += answered
                this.correct += correct
                if (lastCorrect == 0) wrong++
                if (isMastered) mastered++
            }
            banks.getOrPut(bankKey) { BankStat(bankKey) }.apply {
                total++
                this.answered += answered
                this.correct += correct
                if (correct > 0) covered++
                if (lastCorrect == 0) wrong++
            }

            val wrongTimes = answered - correct
            if (wrongTimes > 0) {
                val sortScore = wrongTimes.toLong() * 1_000_000_000_000L + (lastAt.coerceAtLeast(0L) / 1000L)
                hardCandidates.add(
                    sortScore to JSONObject()
                        .put("id", qid).put("q", stem).put("subject", rawSubject).put("level", level)
                        .put("bank", bankKey).put("type", type).put("presented", presented)
                        .put("answered", answered).put("correct", correct).put("wrongTimes", wrongTimes)
                        .put("lastCorrect", if (lastCorrect < 0) JSONObject.NULL else (lastCorrect == 1))
                        .put("tip", tip)
                )
            }
        }
    }

    val querySpan = maxOf(windowDays, 14)
    class DayBucket(val date: String, val label: String, val daysAgo: Int) {
        var answered = 0; var correct = 0; var flashcards = 0; var usedMs = 0L
    }
    val dayMap = LinkedHashMap<String, DayBucket>()
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = childZone
    }
    var earliestStart = todayStart
    for (ago in (querySpan - 1) downTo 0) {
        val dCal = Calendar.getInstance(childZone).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -ago)
        }
        if (ago == querySpan - 1) earliestStart = dCal.timeInMillis
        val dateStr = dayFmt.format(Date(dCal.timeInMillis))
        val label = when (ago) {
            0 -> "今天"
            1 -> "昨天"
            else -> "${dCal.get(Calendar.MONTH) + 1}/${dCal.get(Calendar.DAY_OF_MONTH)}"
        }
        dayMap[dateStr] = DayBucket(dateStr, label, ago)
    }

    readableDatabase.rawQuery(
        "SELECT type,payload,ts FROM events WHERE child=? AND ts>=? AND type IN ('answer','flashcard','usage')",
        arrayOf(child, earliestStart.toString())
    ).use { c ->
        while (c.moveToNext()) {
            val evType = c.getString(0) ?: continue
            val payload = try { JSONObject(c.getString(1) ?: "{}") } catch (_: Exception) { JSONObject() }
            val dateStr = dayFmt.format(Date(c.getLong(2)))
            val bucket = dayMap[dateStr] ?: continue
            when (evType) {
                "usage" -> bucket.usedMs += payload.optLong("milliseconds")
                "answer" -> {
                    bucket.answered++
                    if (payload.optBoolean("correct")) bucket.correct++
                }
                "flashcard" -> {
                    bucket.flashcards++
                    bucket.answered++
                    if (payload.optBoolean("correct")) bucket.correct++
                }
            }
        }
    }

    var recent7Ans = 0; var recent7Cor = 0
    var prev7Ans = 0; var prev7Cor = 0
    var todayAnswered = 0; var todayCorrect = 0
    val dailyArr = JSONArray()
    for (bucket in dayMap.values) {
        if (bucket.daysAgo == 0) {
            todayAnswered = bucket.answered
            todayCorrect = bucket.correct
        }
        if (bucket.daysAgo in 0..6) {
            recent7Ans += bucket.answered
            recent7Cor += bucket.correct
        } else if (bucket.daysAgo in 7..13) {
            prev7Ans += bucket.answered
            prev7Cor += bucket.correct
        }
        if (bucket.daysAgo < windowDays) {
            dailyArr.put(
                JSONObject()
                    .put("date", bucket.date)
                    .put("label", bucket.label)
                    .put("answered", bucket.answered)
                    .put("correct", bucket.correct)
                    .put("accuracy", if (bucket.answered > 0) Math.round(bucket.correct * 100f / bucket.answered) else 0)
                    .put("watchMinutes", Math.round(bucket.usedMs / 6000.0) / 10.0)
                    .put("flashcards", bucket.flashcards)
            )
        }
    }

    val bySubjectArr = JSONArray()
    subjects.values.sortedWith(
        compareByDescending<SubjectStat> { it.wrong }
            .thenBy { if (it.answered > 0) it.accuracy else 101 }
            .thenByDescending { it.total }
    ).forEach { s ->
        bySubjectArr.put(
            JSONObject()
                .put("subject", s.subject).put("total", s.total)
                .put("answered", s.answered).put("correct", s.correct)
                .put("wrong", s.wrong).put("accuracy", s.accuracy).put("mastered", s.mastered)
        )
    }

    val byBankArr = JSONArray()
    banks.values.sortedByDescending { it.total }.forEach { b ->
        byBankArr.put(
            JSONObject()
                .put("bank", b.bank).put("total", b.total)
                .put("covered", b.covered).put("wrong", b.wrong).put("accuracy", b.accuracy)
        )
    }

    hardCandidates.sortByDescending { it.first }
    val hardArr = JSONArray()
    for (i in 0 until minOf(20, hardCandidates.size)) hardArr.put(hardCandidates[i].second)

    val forecastArr = JSONArray()
    for (d in 0 until 7) {
        forecastArr.put(
            JSONObject()
                .put("dayOffset", d)
                .put("label", forecastBounds[d].third)
                .put("dueCount", forecastCounts[d])
        )
    }

    val overview = JSONObject()
        .put("totalQuestions", totalQuestions)
        .put("studiedQuestions", studiedQuestions)
        .put("masteredQuestions", mature)
        .put("learningQuestions", (studiedQuestions - mature).coerceAtLeast(0))
        .put("wrongCount", wrongCount)
        .put("dueNow", dueNow)
        .put("streakDays", computeStreakDays(child))
        .put("totalAnswered", totalAnswered)
        .put("totalCorrect", totalCorrect)
        .put("accuracyPercent", if (totalAnswered > 0) Math.round(totalCorrect * 100f / totalAnswered) else 0)
        .put("recent7dAccuracy", if (recent7Ans > 0) Math.round(recent7Cor * 100f / recent7Ans) else 0)
        .put("prev7dAccuracy", if (prev7Ans > 0) Math.round(prev7Cor * 100f / prev7Ans) else 0)
        .put("todayAnswered", todayAnswered)
        .put("todayCorrect", todayCorrect)

    val maturity = JSONObject()
        .put("unseen", unseen)
        .put("learning", learning)
        .put("young", young)
        .put("mature", mature)

    return JSONObject()
        .put("overview", overview)
        .put("maturity", maturity)
        .put("daily", dailyArr)
        .put("bySubject", bySubjectArr)
        .put("byBank", byBankArr)
        .put("hardQuestions", hardArr)
        .put("forecast", forecastArr)
}
