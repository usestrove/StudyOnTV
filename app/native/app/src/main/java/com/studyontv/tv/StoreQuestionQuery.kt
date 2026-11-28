package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

internal fun LocalStore.presentedImpl(id: String, child: String) {
    writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
    writableDatabase.execSQL(
        "UPDATE question_stats SET presented=presented+1,last_at=? WHERE qid=? AND child=?",
        arrayOf<Any>(clockReading().millis, id, child)
    )
}

internal fun LocalStore.answeredImpl(id: String, correct: Boolean, child: String) {
    val flag = if (correct) 1 else 0
    writableDatabase.execSQL("INSERT OR IGNORE INTO question_stats(qid,child) VALUES(?,?)", arrayOf(id, child))
    writableDatabase.execSQL(
        "UPDATE question_stats SET answered=answered+1,correct=correct+?,last_correct=?,last_at=? WHERE qid=? AND child=?",
        arrayOf<Any>(flag, flag, clockReading().millis, id, child)
    )
}

internal fun LocalStore.idsInBankImpl(bank: String): List<String> {
    require(bank.isNotBlank()) { "请选择题库" }
    val ids = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT id FROM questions WHERE bank=?", arrayOf(bank)).use { cursor ->
        while (cursor.moveToNext()) ids.add(cursor.getString(0))
    }
    return ids
}

internal fun LocalStore.allStemsImpl(): List<String> {
    val out = ArrayList<String>()
    readableDatabase.rawQuery("SELECT stem FROM questions", null).use { c ->
        while (c.moveToNext()) c.getString(0)?.let { out.add(it) }
    }
    return out
}

internal fun LocalStore.questionExistsImpl(id: String): Boolean =
    readableDatabase.rawQuery("SELECT 1 FROM questions WHERE id=? LIMIT 1", arrayOf(id)).use {
        it.moveToFirst()
    }

internal fun LocalStore.buildQuestionOrder(
    spaced: Boolean,
    sortBy: String,
    wrongFirst: Boolean,
    coverageFirst: Boolean,
    newFirst: Boolean
): String {
    val base = if (spaced) {
        "CASE WHEN progress.due_at<=${clockReading().millis} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
    } else when (sortBy) {
        "newest" -> "questions.created_at DESC, questions.rowid DESC"
        "oldest" -> "questions.created_at ASC, questions.rowid ASC"
        else -> "RANDOM()"
    }
    val tiers = mutableListOf<String>()
    if (wrongFirst) tiers.add("CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END")
    if (newFirst) tiers.add("CASE WHEN COALESCE(stats.presented,0)=0 THEN 0 ELSE 1 END")
    if (coverageFirst) tiers.add("CASE WHEN COALESCE(stats.correct,0)=0 THEN 0 ELSE 1 END, COALESCE(stats.presented,0) ASC")
    return (tiers + base).joinToString(", ")
}

internal fun LocalStore.pickQuestionImpl(
    subjects: List<String>,
    levels: List<String>,
    selectedLevel: String,
    banks: List<String>,
    spaced: Boolean,
    wrongFirst: Boolean,
    coverageFirst: Boolean,
    newFirst: Boolean,
    excludeId: String?,
    onlyWrong: Boolean,
    missedOnly: Boolean,
    child: String,
    gradeSubjects: Map<String, List<String>>,
    explicitSubjectFilter: Boolean
): JSONObject? {
    val items = pickDeck(
        count = 1, subjects = subjects, levels = levels, selectedLevel = selectedLevel,
        banks = banks, spaced = spaced, wrongFirst = wrongFirst, coverageFirst = coverageFirst,
        newFirst = newFirst, excludeId = excludeId, onlyWrong = onlyWrong, missedOnly = missedOnly,
        child = child, gradeSubjects = gradeSubjects, explicitSubjectFilter = explicitSubjectFilter
    )
    return if (items.length() > 0) items.getJSONObject(0) else null
}

internal fun LocalStore.pickDeckImpl(
    count: Int,
    subjects: List<String>,
    levels: List<String>,
    selectedLevel: String,
    banks: List<String>,
    spaced: Boolean,
    wrongFirst: Boolean,
    coverageFirst: Boolean,
    newFirst: Boolean,
    excludeId: String?,
    onlyWrong: Boolean,
    missedOnly: Boolean,
    child: String,
    gradeSubjects: Map<String, List<String>>,
    explicitSubjectFilter: Boolean
): JSONArray {
    val where = StringBuilder("questions.enabled=1")
    val args = mutableListOf<String>()
    appendScopeFilters(where, args, subjects, levels, selectedLevel, banks, gradeSubjects, explicitSubjectFilter)
    if (!excludeId.isNullOrEmpty()) {
        where.append(" AND questions.id!=?")
        args.add(excludeId)
    }
    if (onlyWrong) {
        where.append(" AND stats.last_correct=0")
    } else if (missedOnly) {
        where.append(" AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
    }
    val joinArgs = mutableListOf<String>()
    val progressJoin = if (spaced) {
        joinArgs.add(child)
        " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
    } else ""
    joinArgs.add(child)
    val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
    val order = buildQuestionOrder(spaced, "random", wrongFirst, coverageFirst, newFirst)
    return readQuestions(
        "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM $source WHERE $where ORDER BY $order LIMIT ${count.coerceIn(1, 100)}",
        (joinArgs + args).toTypedArray()
    )
}

internal fun LocalStore.distinctLevelsImpl(
    allowedLevels: List<String>,
    subjects: List<String>,
    banks: List<String>,
    gradeSubjects: Map<String, List<String>>,
    explicitSubjectFilter: Boolean
): List<String> {
    val where = StringBuilder("questions.enabled=1 AND TRIM(COALESCE(questions.level,''))!=''")
    val args = mutableListOf<String>()
    appendScopeFilters(where, args, subjects, allowedLevels, "", banks, gradeSubjects, explicitSubjectFilter)
    val out = ArrayList<String>()
    readableDatabase.rawQuery(
        "SELECT DISTINCT questions.level FROM questions WHERE $where",
        args.toTypedArray()
    ).use { c ->
        while (c.moveToNext()) {
            val lvl = c.getString(0)?.trim().orEmpty()
            if (lvl.isNotEmpty()) out.add(lvl)
        }
    }
    return out.distinct().sorted()
}

internal fun LocalStore.questionByIdImpl(id: String, enabledOnly: Boolean, child: String): JSONObject? {
    if (id.isBlank()) return null
    val enabledClause = if (enabledOnly) " AND questions.enabled=1" else ""
    val arr = readQuestions(
        "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
            "WHERE questions.id=?$enabledClause LIMIT 1",
        arrayOf(child, id)
    )
    return if (arr.length() > 0) arr.getJSONObject(0) else null
}

internal fun LocalStore.questionsImpl(
    subject: String?,
    level: String?,
    limit: Int,
    spaced: Boolean,
    enabledOnly: Boolean,
    sortBy: String,
    wrongFirst: Boolean,
    coverageFirst: Boolean,
    newFirst: Boolean,
    child: String
): JSONArray {
    val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
    val args = mutableListOf<String>()
    if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
    if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
    val joinArgs = mutableListOf<String>()
    val progressJoin = if (spaced) {
        joinArgs.add(child)
        " LEFT JOIN question_progress progress ON questions.id=progress.qid AND progress.child=?"
    } else ""
    joinArgs.add(child)
    val source = "questions$progressJoin LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
    val order = buildQuestionOrder(spaced, sortBy, wrongFirst, coverageFirst, newFirst)
    return readQuestions(
        "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 50000)}",
        (joinArgs + args).toTypedArray()
    )
}

/** 题目行的统一映射：出题、题库页、导出共用，保证返回字段口径一致。 */
internal fun LocalStore.readQuestions(sql: String, args: Array<String>): JSONArray {
    val out = JSONArray()
    readableDatabase.rawQuery(sql, args).use { c ->
        while (c.moveToNext()) {
            val details = c.getString(c.getColumnIndexOrThrow("details"))
            val item = if (details.isNullOrEmpty()) JSONObject() else JSONObject(details)
            item.put("id", c.getString(c.getColumnIndexOrThrow("id")))
                .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
                .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
                .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
                .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
                .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
                .put("subject", c.getString(c.getColumnIndexOrThrow("subject")) ?: "")
                .put("level", c.getString(c.getColumnIndexOrThrow("level")) ?: "")
                .put("bank", (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { item.optString("bank").ifBlank { "默认题库" } })
                .put("enabled", c.getInt(c.getColumnIndexOrThrow("enabled")) != 0)
                .put("stats", JSONObject().put("presented", c.getInt(c.getColumnIndexOrThrow("presented")))
                    .put("answered", c.getInt(c.getColumnIndexOrThrow("answered_count")))
                    .put("correct", c.getInt(c.getColumnIndexOrThrow("correct_count")))
                    .put("lastCorrect", c.getInt(c.getColumnIndexOrThrow("last_correct")).let { if (it < 0) JSONObject.NULL else it == 1 }))
            if (!item.has("type")) item.put("type", (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { "single" })
            out.put(item)
        }
    }
    return out
}

internal fun LocalStore.questionPageImpl(
    subject: String?,
    level: String?,
    bank: String?,
    search: String?,
    type: String?,
    difficulty: Int?,
    wrongOnly: Boolean,
    enabledOnly: Boolean,
    sortBy: String,
    limit: Int,
    offset: Int,
    inlineMedia: Boolean,
    child: String
): JSONObject {
    val where = StringBuilder(if (enabledOnly) "questions.enabled=1" else "1=1")
    val args = mutableListOf<String>()
    if (!subject.isNullOrEmpty()) { where.append(" AND questions.subject=?"); args.add(subject) }
    if (!level.isNullOrEmpty()) { where.append(" AND questions.level=?"); args.add(level) }
    if (!bank.isNullOrEmpty()) { where.append(" AND questions.bank=?"); args.add(bank) }
    if (!type.isNullOrEmpty()) { where.append(" AND questions.type=?"); args.add(type) }
    if (difficulty != null) { where.append(" AND questions.difficulty=?"); args.add(difficulty.toString()) }
    if (wrongOnly) where.append(" AND stats.last_correct=0")
    val keyword = search?.trim()?.takeIf { it.isNotEmpty() }
    if (keyword != null) {
        val like = "%${escapeLike(keyword)}%"
        where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
        repeat(5) { args.add(like) }
    }
    val source = "questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?"
    val queryArgs = (listOf(child) + args).toTypedArray()
    val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM $source WHERE $where", queryArgs).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
    val order = when (sortBy) {
        "random" -> "RANDOM()"
        "oldest" -> "questions.created_at ASC, questions.rowid ASC"
        "diff", "difficulty" -> "questions.difficulty DESC, questions.rowid DESC"
        "type" -> "questions.type ASC, questions.difficulty DESC, questions.rowid DESC"
        else -> "questions.created_at DESC, questions.rowid DESC"
    }
    val page = limit.coerceIn(1, 50000)
    val start = offset.coerceAtLeast(0)
    val rawItems = readQuestions(
        "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM $source WHERE $where ORDER BY $order LIMIT $page OFFSET $start",
        queryArgs
    )
    val items = if (!inlineMedia) rawItems else JSONArray().also { arr ->
        for (i in 0 until rawItems.length()) arr.put(MediaStore.inlineQuestionMedia(mediaDir, rawItems.getJSONObject(i)))
    }
    return JSONObject().put("items", items).put("total", total).put("offset", start).put("limit", page)
}

/** 题库页的筛选项和计数：题库名 / 科目 / 年级 + 总题数、启用、暂停、当前孩子的错题数。 */
internal fun LocalStore.questionMetaImpl(child: String): JSONObject {
    val banks = sortedSetOf<String>()
    val subjects = sortedSetOf<String>()
    val levels = sortedSetOf<String>()
    var total = 0
    var enabled = 0
    var wrong = 0
    readableDatabase.rawQuery(
        "SELECT questions.bank,questions.subject,questions.level,questions.enabled," +
            "CASE WHEN stats.last_correct=0 THEN 1 ELSE 0 END AS wrong " +
            "FROM questions LEFT JOIN question_stats stats ON questions.id=stats.qid AND stats.child=?",
        arrayOf(child)
    ).use { c ->
        while (c.moveToNext()) {
            total++
            (c.getString(0) ?: "").ifBlank { "默认题库" }.let { banks.add(it) }
            (c.getString(1) ?: "").takeIf { it.isNotBlank() }?.let { subjects.add(it) }
            (c.getString(2) ?: "").takeIf { it.isNotBlank() }?.let { levels.add(it) }
            if (c.getInt(3) != 0) enabled++
            if (c.getInt(4) == 1) wrong++
        }
    }
    return JSONObject()
        .put("banks", JSONArray(banks.toList()))
        .put("subjects", JSONArray(subjects.toList()))
        .put("levels", JSONArray(levels.toList()))
        .put("total", total).put("enabled", enabled).put("paused", total - enabled).put("wrong", wrong)
}

internal fun LocalStore.countQuestionsImpl(): Int =
    readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
