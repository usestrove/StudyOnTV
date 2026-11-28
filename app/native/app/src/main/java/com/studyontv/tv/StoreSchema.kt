package com.studyontv.tv

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal fun LocalStore.createStoreSchema(db: SQLiteDatabase) {
    // bank / type 写成独立列以走索引，details 仍是完整题目（导出格式不变）
    db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT, bank TEXT NOT NULL DEFAULT '默认题库', type TEXT NOT NULL DEFAULT 'single')")
    createQuestionIndexes(db)
    db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER, child TEXT NOT NULL DEFAULT '')")
    db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    createReviewsTable(db)
    createProgressTable(db)
    createQuestionStatsTable(db)
    createChildrenTable(db)
}

internal fun LocalStore.upgradeStoreSchema(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) db.execSQL("ALTER TABLE questions ADD COLUMN details TEXT")
    if (oldVersion < 3) createReviewsTable(db)
    if (oldVersion < 4) createProgressTable(db)
    if (oldVersion < 5) compactUsage(db)
    if (oldVersion < 6) createQuestionStatsTable(db)
    if (oldVersion < 7) {
        // 多孩子（#132）：历史 / 使用时长 / 艾宾浩斯进度都按孩子隔离，旧的记录归第一个孩子
        createChildrenTable(db)
        addColumn(db, "events", "child", "TEXT NOT NULL DEFAULT ''")
        migrateProgressForChildren(db)
    }
    // 错题库（#136）：记录每题最近一次作答的结果，-1 表示还没作答过
    if (oldVersion < 8) addColumn(db, "question_stats", "last_correct", "INTEGER NOT NULL DEFAULT -1")
    if (oldVersion < 9) {
        // Android 5–11 SQLite 不支持 JSON_EXTRACT，回填必须在 Kotlin 层解析 details
        addColumn(db, "questions", "bank", "TEXT NOT NULL DEFAULT '默认题库'")
        addColumn(db, "questions", "type", "TEXT NOT NULL DEFAULT 'single'")
        backfillQuestionColumns(db)
        createQuestionIndexes(db)
    }
    if (oldVersion < 10) {
        // 第 10 版：question_stats 改为按孩子隔离的 (qid, child) 复合主键，并迁出内联 Base64 媒体
        migrateQuestionStatsForChildren(db)
        migrateInlineMedia(db)
    }
    if (oldVersion < 11) {
        // 第 11 版（ST-610）：多孩子独立切换密码（加盐 SHA-256 哈希，默认空串表示未设密码）
        addColumn(db, "children", "pin_hash", "TEXT NOT NULL DEFAULT ''")
        addColumn(db, "children", "pin_salt", "TEXT NOT NULL DEFAULT ''")
    }
}

internal fun createQuestionIndexes(db: SQLiteDatabase) {
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_bank ON questions(bank)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_type ON questions(type)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_questions_enabled ON questions(enabled)")
}

/** 把老库里 details 的 bank / type 抄进新列；分批读，避免几万道题一次性塞进内存。 */
internal fun backfillQuestionColumns(db: SQLiteDatabase) {
    val statement = db.compileStatement("UPDATE questions SET bank=?,type=? WHERE id=?")
    try {
        var last = -1L
        while (true) {
            val rows = ArrayList<Triple<String, String, String>>()
            db.rawQuery("SELECT rowid,id,details FROM questions WHERE rowid>? ORDER BY rowid LIMIT 500", arrayOf(last.toString())).use { c ->
                while (c.moveToNext()) {
                    last = c.getLong(0)
                    val details = try { JSONObject(c.getString(2)?.takeIf { it.isNotBlank() } ?: "{}") } catch (_: Exception) { JSONObject() }
                    rows.add(Triple(c.getString(1), details.optString("bank").ifBlank { "默认题库" }, details.optString("type").ifBlank { "single" }))
                }
            }
            if (rows.isEmpty()) break
            rows.forEach { (id, bank, type) ->
                statement.clearBindings()
                statement.bindString(1, bank)
                statement.bindString(2, type)
                statement.bindString(3, id)
                statement.executeUpdateDelete()
            }
        }
    } finally {
        statement.close()
    }
}

internal fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info($table)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
    if (column !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
}

internal fun createChildrenTable(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, pin_hash TEXT NOT NULL DEFAULT '', pin_salt TEXT NOT NULL DEFAULT '')")
}

/** 旧的 question_progress 以 qid 为主键，多孩子后必须变成 (qid, child) 复合主键 */
internal fun migrateProgressForChildren(db: SQLiteDatabase) {
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(question_progress)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
    if ("child" in columns) return
    db.execSQL("ALTER TABLE question_progress RENAME TO question_progress_legacy")
    createProgressTable(db)
    db.execSQL("INSERT INTO question_progress(qid,child,repetitions,interval_days,ease,due_at,updated_at) " +
        "SELECT qid,'',repetitions,interval_days,ease,due_at,updated_at FROM question_progress_legacy")
    db.execSQL("DROP TABLE question_progress_legacy")
}

/** 旧的 question_stats 整机共用一份，升级为 (qid, child) 复合主键并把旧统计归到默认孩子名下。 */
internal fun migrateQuestionStatsForChildren(db: SQLiteDatabase) {
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(question_stats)", null).use { while (it.moveToNext()) columns.add(it.getString(1)) }
    if (columns.isEmpty()) {
        createQuestionStatsTable(db)
        return
    }
    if ("child" in columns) return
    val lastCol = if ("last_correct" in columns) "last_correct" else "-1"
    db.execSQL("DROP TABLE IF EXISTS question_stats_legacy")
    db.execSQL("ALTER TABLE question_stats RENAME TO question_stats_legacy")
    createQuestionStatsTable(db)
    db.execSQL(
        "INSERT OR REPLACE INTO question_stats(qid,child,presented,answered,correct,last_at,last_correct) " +
            "SELECT qid,'',presented,answered,correct,last_at,$lastCol FROM question_stats_legacy"
    )
    db.execSQL("DROP TABLE question_stats_legacy")
}

/** 旧版本每秒写一条 usage 事件，升级时按小时合并成一条以解决加载卡顿（Issue #2）。 */
internal fun compactUsage(db: SQLiteDatabase) {
    val buckets = LinkedHashMap<Long, Long>()
    db.rawQuery("SELECT payload,ts FROM events WHERE type='usage'", null).use { c ->
        while (c.moveToNext()) {
            val ms = try { JSONObject(c.getString(0)).optLong("milliseconds") } catch (_: Exception) { 0L }
            val hour = c.getLong(1) / 3600000 * 3600000
            buckets[hour] = (buckets[hour] ?: 0L) + ms
        }
    }
    db.beginTransaction()
    try {
        db.execSQL("DELETE FROM events WHERE type='usage'")
        buckets.forEach { (hour, ms) ->
            if (ms > 0) db.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any>("usage", JSONObject().put("milliseconds", ms).toString(), hour))
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

internal fun createQuestionStatsTable(db: SQLiteDatabase) {
    // IF NOT EXISTS 保证升级中断/重入不报错；last_correct=-1 表示尚未作答
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS question_stats(" +
            "qid TEXT NOT NULL, " +
            "child TEXT NOT NULL DEFAULT '', " +
            "presented INTEGER NOT NULL DEFAULT 0, " +
            "answered INTEGER NOT NULL DEFAULT 0, " +
            "correct INTEGER NOT NULL DEFAULT 0, " +
            "last_at INTEGER NOT NULL DEFAULT 0, " +
            "last_correct INTEGER NOT NULL DEFAULT -1, " +
            "PRIMARY KEY(qid, child))"
    )
}

internal fun createProgressTable(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS question_progress(qid TEXT NOT NULL, child TEXT NOT NULL DEFAULT '', repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(qid, child))")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_question_due ON question_progress(due_at)")
}

internal fun createReviewsTable(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY, nonce TEXT UNIQUE, state TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)")
}
