package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/** 解析加练队列 JSON 数组，过滤空值并保序去重。 */
internal fun parseExtraQueueImpl(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
    val out = LinkedHashSet<String>()
    for (i in 0 until arr.length()) {
        val id = arr.optString(i).trim()
        if (id.isNotEmpty()) out.add(id)
    }
    return out.toList()
}

/** 将新增题号合并进加练队列（保序去重），返回合并后的队列与实际新增数。 */
internal fun mergeExtraQueueImpl(existing: List<String>, additions: List<String>): Pair<List<String>, Int> {
    val merged = LinkedHashSet<String>()
    existing.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
    val before = merged.size
    additions.forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let { merged.add(it) } }
    return merged.toList() to (merged.size - before)
}

internal fun LocalStore.extraQueueImpl(child: String): List<String> =
    LocalStore.parseExtraQueue(rawGet(scoped("extra_queue", child), "[]"))

internal fun LocalStore.addToExtraQueueImpl(ids: List<String>, child: String): Int {
    val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (cleaned.isEmpty()) return 0
    val valid = LinkedHashSet<String>()
    val chunkSize = 500
    for (start in cleaned.indices step chunkSize) {
        val chunk = cleaned.subList(start, minOf(start + chunkSize, cleaned.size))
        readableDatabase.rawQuery(
            "SELECT id FROM questions WHERE enabled=1 AND id IN (${sqlPlaceholders(chunk.size)})",
            chunk.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { valid.add(it) }
        }
    }
    val orderedValid = cleaned.filter { it in valid }
    if (orderedValid.isEmpty()) return 0
    val existing = extraQueue(child)
    val (merged, added) = LocalStore.mergeExtraQueue(existing, orderedValid)
    if (added > 0) {
        rawSet(scoped("extra_queue", child), JSONArray(merged).toString())
    }
    return added
}

internal fun LocalStore.popExtraQueueImpl(id: String, child: String): Boolean {
    val target = id.trim()
    if (target.isEmpty()) return false
    val existing = extraQueue(child)
    if (target !in existing) return false
    val remaining = existing.filter { it != target }
    rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
    return true
}

internal fun LocalStore.clearExtraQueueImpl(child: String) {
    rawSet(scoped("extra_queue", child), "[]")
}

/** 错题库查询（ST-601 / #159）：以 question_stats.last_correct=0 为唯一真源，按孩子隔离。 */
internal fun LocalStore.listWrongQuestionsImpl(
    subject: String?,
    level: String?,
    bank: String?,
    q: String?,
    child: String
): JSONObject {
    val where = StringBuilder("questions.enabled=1 AND stats.last_correct=0")
    val args = mutableListOf(child)
    if (!subject.isNullOrBlank()) {
        where.append(" AND questions.subject=?")
        args.add(subject.trim())
    }
    if (!level.isNullOrBlank()) {
        where.append(" AND questions.level=?")
        args.add(level.trim())
    }
    if (!bank.isNullOrBlank()) {
        val b = bank.trim()
        if (b == "默认题库") {
            where.append(" AND (questions.bank=? OR questions.bank='')")
        } else {
            where.append(" AND questions.bank=?")
        }
        args.add(b)
    }
    val keyword = q?.trim()?.takeIf { it.isNotEmpty() }
    if (keyword != null) {
        val like = "%${escapeLike(keyword)}%"
        where.append(" AND (questions.stem LIKE ? ESCAPE '\\' OR questions.tip LIKE ? ESCAPE '\\' OR questions.options LIKE ? ESCAPE '\\' OR questions.subject LIKE ? ESCAPE '\\' OR questions.level LIKE ? ESCAPE '\\')")
        repeat(5) { args.add(like) }
    }
    val items = JSONArray()
    readableDatabase.rawQuery(
        "SELECT questions.*,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_at,0) AS last_at," +
            "COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
            "WHERE $where ORDER BY stats.last_at DESC, (stats.answered - stats.correct) DESC, questions.rowid DESC LIMIT 500",
        args.toTypedArray()
    ).use { c ->
        while (c.moveToNext()) {
            val details = c.getString(c.getColumnIndexOrThrow("details"))
            val full = if (details.isNullOrEmpty()) JSONObject() else try { JSONObject(details) } catch (_: Exception) { JSONObject() }
            val id = c.getString(c.getColumnIndexOrThrow("id"))
            val stem = c.getString(c.getColumnIndexOrThrow("stem"))
            val options = try { JSONArray(c.getString(c.getColumnIndexOrThrow("options"))) } catch (_: Exception) { JSONArray() }
            val k = c.getInt(c.getColumnIndexOrThrow("answer"))
            val tip = c.getString(c.getColumnIndexOrThrow("tip")) ?: ""
            val subj = c.getString(c.getColumnIndexOrThrow("subject")) ?: ""
            val lvl = c.getString(c.getColumnIndexOrThrow("level")) ?: ""
            val bankName = (c.getString(c.getColumnIndexOrThrow("bank")) ?: "").ifBlank { full.optString("bank").ifBlank { "默认题库" } }
            val type = (c.getString(c.getColumnIndexOrThrow("type")) ?: "").ifBlank { full.optString("type").ifBlank { "single" } }
            full.put("a", options).put("k", k).put("type", type)
            val answered = c.getInt(c.getColumnIndexOrThrow("answered_count"))
            val correct = c.getInt(c.getColumnIndexOrThrow("correct_count"))
            val lastAt = c.getLong(c.getColumnIndexOrThrow("last_at"))
            val lastCorrect = c.getInt(c.getColumnIndexOrThrow("last_correct"))
            val wrongCount = (answered - correct).coerceAtLeast(if (lastCorrect == 0) 1 else 0)
            items.put(
                JSONObject()
                    .put("id", id)
                    .put("q", stem)
                    .put("type", type)
                    .put("subject", subj)
                    .put("level", lvl)
                    .put("bank", bankName)
                    .put("options", options)
                    .put("answer", LearningEngine.formatAnswerDisplay(full))
                    .put("explain", tip)
                    .put("answered", answered)
                    .put("correct", correct)
                    .put("wrongCount", wrongCount)
                    .put("lastAt", lastAt)
                    .put("lastCorrect", lastCorrect == 1)
            )
        }
    }
    val extraPool = rawGet(scoped("extra_pool", child), "0").toLongOrNull() ?: 0L
    val extraQueueIds = extraQueue(child)
    return JSONObject()
        .put("items", items)
        .put("total", items.length())
        .put("extraPool", extraPool)
        .put("extraQueueIds", JSONArray(extraQueueIds))
}

/** 非破坏性“标为已掌握”：仅重置统计并移出 extra_queue，不删除原题目。 */
internal fun LocalStore.markWrongMasteredImpl(ids: List<String>, all: Boolean, child: String): Int {
    val db = writableDatabase
    var updated = 0
    db.beginTransaction()
    try {
        if (all) {
            val masteredIds = ArrayList<String>()
            db.rawQuery(
                "SELECT qid FROM question_stats WHERE child=? AND (last_correct=0 OR answered>correct)",
                arrayOf(child)
            ).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { masteredIds.add(it) }
            }
            val stmt = db.compileStatement(
                "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                    "WHERE child=? AND (last_correct=0 OR answered>correct)"
            )
            try {
                stmt.bindString(1, child)
                updated = stmt.executeUpdateDelete()
            } finally {
                stmt.close()
            }
            if (masteredIds.isNotEmpty()) {
                val remaining = extraQueue(child).filter { it !in masteredIds.toSet() }
                rawSet(scoped("extra_queue", child), JSONArray(remaining).toString())
            }
        } else {
            val cleaned = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isNotEmpty()) {
                val stmt = db.compileStatement(
                    "UPDATE question_stats SET last_correct=1, correct=CASE WHEN correct<answered THEN answered ELSE correct END " +
                        "WHERE qid=? AND child=? AND (last_correct=0 OR answered>correct)"
                )
                try {
                    for (qid in cleaned) {
                        stmt.clearBindings()
                        stmt.bindString(1, qid)
                        stmt.bindString(2, child)
                        updated += stmt.executeUpdateDelete()
                    }
                } finally {
                    stmt.close()
                }
                val cleanedSet = cleaned.toSet()
                val existing = extraQueue(child)
                if (existing.any { it in cleanedSet }) {
                    rawSet(scoped("extra_queue", child), JSONArray(existing.filter { it !in cleanedSet }).toString())
                }
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    return updated
}

/** 供 AI 错题举一反三抽取典型薄弱题样本（优先当前错题，其次累计失误次数多的题）。 */
internal fun LocalStore.wrongQuestionsSampleImpl(subject: String?, limit: Int, child: String): List<JSONObject> {
    val where = StringBuilder("questions.enabled=1 AND (stats.last_correct=0 OR COALESCE(stats.answered,0)>COALESCE(stats.correct,0))")
    val args = mutableListOf(child)
    if (!subject.isNullOrBlank()) {
        where.append(" AND questions.subject=?")
        args.add(subject.trim())
    }
    val arr = readQuestions(
        "SELECT questions.*,COALESCE(stats.presented,0) AS presented,COALESCE(stats.answered,0) AS answered_count," +
            "COALESCE(stats.correct,0) AS correct_count,COALESCE(stats.last_correct,-1) AS last_correct " +
            "FROM questions JOIN question_stats stats ON questions.id=stats.qid AND stats.child=? " +
            "WHERE $where ORDER BY CASE WHEN stats.last_correct=0 THEN 0 ELSE 1 END, " +
            "(stats.answered-stats.correct) DESC, stats.last_at DESC LIMIT ${limit.coerceIn(1, 20)}",
        args.toTypedArray()
    )
    return (0 until arr.length()).map { arr.getJSONObject(it) }
}
