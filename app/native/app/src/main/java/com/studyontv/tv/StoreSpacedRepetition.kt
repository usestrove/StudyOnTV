package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 艾宾浩斯 / Anki（SM-2 改良）间隔计算纯函数。
 *
 * 抽离成无副作用对象有两个原因：
 * 1. 背诵闪卡正面/背面要提前展示「重来 / 困难 / 良好 / 简单」四个按钮对应的下次复习间隔，必须和点击后实际落库的值完全一致；
 * 2. 纯 JVM 单元测试可以直接验证各评分档位的演进，不依赖 Android SQLite。
 */
object SpacedRepetition {
    data class State(val repetitions: Int = 0, val intervalDays: Long = 0L, val ease: Double = 2.5)
    data class Step(val repetitions: Int, val intervalDays: Long, val ease: Double, val dueAt: Long)

    fun next(current: State, quality: Int, nowMs: Long): Step {
        val q = quality.coerceIn(0, 5)
        var repetitions = current.repetitions
        var interval = current.intervalDays
        val dueAt: Long
        if (q < 3) {
            repetitions = 0
            interval = 0L
            dueAt = nowMs + 10 * 60 * 1000L
        } else {
            repetitions++
            val standardGood = when (repetitions) {
                1 -> 1L
                2 -> 6L
                else -> kotlin.math.round(current.intervalDays * current.ease).toLong().coerceIn(1L, 36500L)
            }
            interval = when (q) {
                3 -> if (current.repetitions == 0) 1L
                else maxOf(1L, kotlin.math.round(current.intervalDays * 1.2).toLong()).coerceIn(1L, 36500L)
                5 -> when (repetitions) {
                    1 -> 4L
                    2 -> maxOf(7L, kotlin.math.round(current.intervalDays.coerceAtLeast(1L) * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                    else -> maxOf(standardGood + 1L, kotlin.math.round(current.intervalDays * current.ease * 1.3).toLong()).coerceIn(1L, 36500L)
                }
                else -> standardGood
            }
            dueAt = nowMs + interval * 86_400_000L
        }
        val nextEase = (current.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        return Step(repetitions, interval, nextEase, dueAt)
    }

    fun previewLabels(current: State): JSONObject {
        val hardDays = next(current, 3, 0L).intervalDays.coerceAtLeast(1L)
        val goodDays = next(current, 4, 0L).intervalDays.coerceAtLeast(1L)
        val easyDays = maxOf(goodDays + 1L, next(current, 5, 0L).intervalDays)
        return JSONObject()
            .put("again", "10分钟")
            .put("hard", "${hardDays}天")
            .put("good", "${goodDays}天")
            .put("easy", "${easyDays}天")
    }
}

/** 间隔复习纳入范围判断（repetitionStats / studySummary / learningAnalytics 唯一真理源）。 */
internal fun inSpacedScopeImpl(wrongOnly: Boolean, lastCorrect: Int, answered: Int, correct: Int): Boolean =
    !wrongOnly || lastCorrect == 0 || answered > correct

internal fun LocalStore.progressStateImpl(id: String, child: String): SpacedRepetition.State =
    readableDatabase.rawQuery(
        "SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=? AND child=?",
        arrayOf(id, child)
    ).use {
        if (it.moveToFirst()) SpacedRepetition.State(it.getInt(0), it.getLong(1), it.getDouble(2))
        else SpacedRepetition.State()
    }

internal fun LocalStore.recordProgressImpl(id: String, quality: Int) {
    val child = activeChild()
    val current = progressState(id, child)
    val now = clockReading().millis
    val step = SpacedRepetition.next(current, quality, now)
    writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
        put("qid", id); put("child", child); put("repetitions", step.repetitions)
        put("interval_days", step.intervalDays); put("ease", step.ease)
        put("due_at", step.dueAt); put("updated_at", now)
    }, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun LocalStore.repetitionStatsImpl(child: String): JSONObject {
    val wrongOnly = config(child).optString("spacedScope") == "wrong_only"
    val nowStr = clockReading().millis.toString()
    val sql = if (wrongOnly) {
        "SELECT COUNT(*),COALESCE(SUM(CASE WHEN question_progress.due_at<=? AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0)) THEN 1 ELSE 0 END),0) " +
            "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
            "LEFT JOIN question_stats stats ON stats.qid=questions.id AND stats.child=question_progress.child " +
            "WHERE questions.enabled=1 AND question_progress.child=?"
    } else {
        "SELECT COUNT(*),COALESCE(SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END),0) " +
            "FROM question_progress JOIN questions ON questions.id=question_progress.qid " +
            "WHERE questions.enabled=1 AND question_progress.child=?"
    }
    return readableDatabase.rawQuery(sql, arrayOf(nowStr, child)).use {
        if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1))
        else JSONObject().put("studied", 0).put("due", 0)
    }
}

/** 答题覆盖：口径是当前孩子「答对过一次」（stats.correct>0），和出题时的覆盖优先用同一套判断。 */
internal fun LocalStore.coverageImpl(
    sample: Int,
    child: String,
    gradeSubjects: Map<String, List<String>>
): JSONObject {
    val banks = LinkedHashMap<String, IntArray>()
    val pending = ArrayList<JSONObject>()
    var total = 0
    var covered = 0
    var untouched = 0
    val where = StringBuilder("questions.enabled=1")
    val args = mutableListOf(child)
    val filter = LocalStore.buildScopeFilter(gradeSubjects = gradeSubjects)
    where.append(filter.clause)
    args.addAll(filter.args)
    readableDatabase.rawQuery(
        "SELECT questions.id,questions.stem,questions.subject,questions.level,questions.bank," +
            "COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count " +
            "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? WHERE $where",
        args.toTypedArray()
    ).use { c ->
        while (c.moveToNext()) {
            val presented = c.getInt(5)
            val answered = c.getInt(6)
            val correct = c.getInt(7)
            val bank = (c.getString(4) ?: "").ifBlank { "默认题库" }
            val bucket = banks.getOrPut(bank) { intArrayOf(0, 0) }
            total++
            bucket[0]++
            if (correct > 0) {
                covered++
                bucket[1]++
            } else {
                if (presented == 0) untouched++
                pending.add(JSONObject().put("id", c.getString(0) ?: "")
                    .put("q", (c.getString(1) ?: "").take(80))
                    .put("bank", bank)
                    .put("subject", c.getString(2) ?: "")
                    .put("level", c.getString(3) ?: "")
                    .put("presented", presented)
                    .put("answered", answered))
            }
        }
    }
    pending.sortWith(compareBy<JSONObject>({ it.optInt("presented") }, { it.optInt("answered") }))
    val items = JSONArray()
    for (i in 0 until minOf(pending.size, sample)) items.put(pending[i])
    val bankList = JSONArray()
    banks.entries.sortedByDescending { it.value[0] }.forEach { entry ->
        val counts = entry.value
        bankList.put(JSONObject().put("name", entry.key).put("total", counts[0]).put("covered", counts[1])
            .put("percent", if (counts[0] == 0) 0 else Math.round(counts[1] * 100f / counts[0])))
    }
    return JSONObject().put("total", total).put("covered", covered)
        .put("percent", if (total == 0) 0 else Math.round(covered * 100f / total))
        .put("pending", pending.size).put("untouched", untouched)
        .put("banks", bankList).put("items", items)
}
