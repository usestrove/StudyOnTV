package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BACKUP_MAX_DATA_URL_LEN = 2_800_000
private const val BACKUP_MAX_DECODED_BYTES = 2_000_000

/**
 * 备份恢复 / 局域网同步时的题目媒体自愈：
 * - 允许旧版备份中较大的内联 Data URL（上限 2 MB）直接转存本地媒体文件，避免误触发 900 KB 录入限制；
 * - 遇到损坏/失效的配图或音频地址时仅摘除该字段（并保持 optionImages 下标对齐），不让单道坏图拖垮整库恢复。
 */
internal fun sanitizeBackupQuestionMedia(item: JSONObject, mediaDir: File): Pair<JSONObject, Int> {
    val hasMediaField = item.optString("image").isNotBlank() ||
        item.optString("audio").isNotBlank() ||
        item.has("images") ||
        item.has("optionImages")
    if (!hasMediaField) return item to 0

    val copy = JSONObject(item.toString())
    var stripped = 0

    fun normalizeOrStrip(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            MediaStore.normalizeImageRef(
                mediaDir,
                trimmed,
                maxDataUrlLen = BACKUP_MAX_DATA_URL_LEN,
                maxDecodedBytes = BACKUP_MAX_DECODED_BYTES
            )
        } catch (_: IllegalArgumentException) {
            stripped++
            ""
        }
    }

    if (copy.optString("image").isNotBlank()) {
        copy.put("image", normalizeOrStrip(copy.optString("image")))
    }
    copy.optJSONArray("images")?.let { rawImages ->
        val cleaned = JSONArray()
        for (i in 0 until minOf(rawImages.length(), 4)) {
            val v = rawImages.optString(i).trim()
            if (v.isEmpty()) continue
            val normalized = normalizeOrStrip(v)
            if (normalized.isNotEmpty()) cleaned.put(normalized)
        }
        copy.put("images", cleaned)
        if (copy.optString("image").isEmpty() && cleaned.length() > 0) {
            copy.put("image", cleaned.getString(0))
        }
    }
    copy.optJSONArray("optionImages")?.let { rawOpts ->
        val cleaned = JSONArray()
        val optTexts = copy.optJSONArray("a")
        for (i in 0 until minOf(rawOpts.length(), 8)) {
            val v = rawOpts.optString(i).trim()
            val normalized = if (v.isEmpty()) "" else normalizeOrStrip(v)
            cleaned.put(normalized)
            if (v.isNotEmpty() && normalized.isEmpty() && optTexts != null && i < optTexts.length() && optTexts.optString(i).isBlank()) {
                optTexts.put(i, "（配图已失效）")
            }
        }
        copy.put("optionImages", cleaned)
    }
    val rawAudio = copy.optString("audio").trim()
    if (rawAudio.isNotEmpty()) {
        val validAudio = (rawAudio.startsWith("https://") || rawAudio.startsWith("http://")) && rawAudio.length <= 2048
        val conflictWithListen = copy.optString("listen").trim().isNotEmpty()
        if (!validAudio || conflictWithListen) {
            copy.put("audio", "")
            stripped++
        }
    }
    return copy to stripped
}

internal fun LocalStore.applyScopedSyncImpl(payload: JSONObject, scopes: Set<String>, mode: String): JSONObject {
    require(payload.optString("format") == "studyontv-backup") { "不是有效的题了个题同步数据包" }
    require(mode in listOf("replace", "merge")) { "同步模式仅支持 replace 或 merge" }
    val validScopes = LocalStore.normalizeSyncScopes(scopes)
    require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }

    val replaceQuestions = mode == "replace"
    val db = writableDatabase
    val existingGrading = try { JSONObject(rawGet("grading_settings", "{}")) } catch (_: Exception) { JSONObject() }
    val existingSpeech = try { JSONObject(rawGet("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }
    var syncedQuestions = 0
    var skippedQuestions = 0
    var strippedMedia = 0

    synchronized(childLock) {
        db.beginTransaction()
        try {
            if ("config" in validScopes) {
                payload.optJSONArray("children")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), LocalStore.MAX_CHILDREN)) {
                        val c = arr.optJSONObject(i) ?: continue
                        val id = c.optString("id")
                        if (!LocalStore.isValidChildId(id)) continue
                        val name = c.optString("name").trim().ifEmpty { "孩子${i + 1}" }.take(12)
                        db.insertWithOnConflict("children", null, ContentValues().apply {
                            put("id", id)
                            put("name", name)
                            put("created_at", c.optLong("createdAt", System.currentTimeMillis()))
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                ensureDefaultChild()

                payload.optJSONObject("kv")?.let { kv ->
                    val keys = kv.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        // 严格白名单：只允许学习规则 config / child:<id>:config，绝不写入任何单机运行态或密码键
                        if (!LocalStore.isConfigSyncKvKey(k)) continue
                        val sanitized = LocalStore.sanitizeKvValueForLocalOnly(k, kv.optString(k, ""))
                        db.insertWithOnConflict("kv", null, ContentValues().apply {
                            put("k", k); put("v", sanitized)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }
            }

            if ("questions" in validScopes) {
                payload.optJSONArray("questions")?.let { arr ->
                    if (replaceQuestions) {
                        db.delete("questions", null, null)
                    }
                    for (i in 0 until minOf(arr.length(), 50000)) {
                        val q = arr.optJSONObject(i) ?: continue
                        val qid = q.optString("id")
                        if (!replaceQuestions && qid.isNotEmpty() && questionExists(qid)) continue
                        val (cleaned, stripped) = sanitizeBackupQuestionMedia(q, mediaDir)
                        strippedMedia += stripped
                        try {
                            upsertQuestion(cleaned)
                            syncedQuestions++
                        } catch (e: IllegalArgumentException) {
                            skippedQuestions++
                            Diag.warn("Store", "scoped sync skipped invalid question id=$qid: ${e.message}")
                        }
                    }
                }
            }

            if ("speech" in validScopes) {
                payload.optJSONObject("speech")?.let { s ->
                    val next = JSONObject(s.toString())
                    for (uiKey in listOf("deepseekInherited", "deepseekReady")) next.remove(uiKey)
                    for (secret in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
                        next.remove(secret + "Set")
                        if (next.optString(secret).isBlank() && existingSpeech.optString(secret).isNotBlank()) {
                            next.put(secret, existingSpeech.getString(secret))
                        }
                    }
                    rawSet("speech_settings", next.toString())
                }
            }

            if ("grading" in validScopes) {
                payload.optJSONObject("grading")?.let { g ->
                    val next = JSONObject(g.toString())
                    for (uiKey in listOf("hasKey", "chatUrl", "providers", "clearKey")) next.remove(uiKey)
                    if (next.optString("apiKey").isBlank() && existingGrading.optString("apiKey").isNotBlank()) {
                        next.put("apiKey", existingGrading.getString("apiKey"))
                    }
                    rawSet("grading_settings", next.toString())
                }
            }

            configCache.clear()
            cachedChild = null
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    if (strippedMedia > 0) Diag.warn("Store", "scoped sync sanitized $strippedMedia invalid media ref(s)")
    if ("questions" in validScopes && !db.inTransaction()) pruneMedia()
    val totalQ = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    return JSONObject()
        .put("ok", true)
        .put("scopes", JSONArray(validScopes.sorted()))
        .put("mode", mode)
        .put("children", children().length())
        .put("questions", totalQ)
        .put("syncedQuestions", syncedQuestions)
        .put("skippedQuestions", skippedQuestions)
        .put("strippedMedia", strippedMedia)
}

internal fun LocalStore.restoreBackupImpl(backup: JSONObject, mode: String, scopes: Set<String>?): JSONObject {
    if (scopes != null) return applyScopedSync(backup, scopes, mode)
    require(backup.optString("format") == "studyontv-backup") { "不是有效的题了个题备份文件" }
    require(mode in listOf("replace", "merge")) { "恢复模式仅支持 replace 或 merge" }
    val replace = mode == "replace"
    val db = writableDatabase

    // 先保存本机已有的 API Key，防止脱敏备份把本机配好的密钥冲成空
    val existingGrading = try { JSONObject(rawGet("grading_settings", "{}")) } catch (_: Exception) { JSONObject() }
    val existingSpeech = try { JSONObject(rawGet("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }
    var skippedQuestions = 0
    var strippedMedia = 0

    // 统一锁顺序：永远先 childLock、再 SQLite 写事务，避免与 switchChild()/activeChild() 产生 AB-BA 死锁
    synchronized(childLock) {
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("children", null, null)
                db.delete("events", null, null)
                db.delete("reviews", null, null)
                db.delete("question_progress", null, null)
                db.delete("question_stats", null, null)
                db.delete("kv", "k NOT IN ('admin_token','grading_settings','speech_settings','onboarded')", null)
                if (backup.optBoolean("includeQuestions", true) && backup.has("questions")) {
                    db.delete("questions", null, null)
                }
            }

            backup.optJSONArray("children")?.let { arr ->
                for (i in 0 until minOf(arr.length(), LocalStore.MAX_CHILDREN)) {
                    val c = arr.optJSONObject(i) ?: continue
                    val id = c.optString("id")
                    if (!LocalStore.isValidChildId(id)) continue
                    val name = c.optString("name").trim().ifEmpty { "孩子${i + 1}" }.take(12)
                    db.insertWithOnConflict("children", null, ContentValues().apply {
                        put("id", id)
                        put("name", name)
                        put("created_at", c.optLong("createdAt", System.currentTimeMillis()))
                    }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            ensureDefaultChild()

            val priorUsageDays = mutableMapOf<String, Long>()
            if (!replace) {
                db.rawQuery("SELECT k,v FROM kv WHERE k='usage_day' OR k LIKE 'child:%:usage_day'", null).use { rows ->
                    while (rows.moveToNext()) {
                        val start = rows.getString(1).toLongOrNull() ?: continue
                        priorUsageDays[rows.getString(0)] = start
                    }
                }
            }

            val usageDayFields = setOf("usage_day_rounds", "usage_day_source", "usage_day_rejected_system")
            backup.optJSONObject("kv")?.let { kv ->
                val keys = kv.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in LocalStore.SECRET_KV_KEYS || LocalStore.isEphemeralKvKey(k)) continue
                    // 合并备份时，已有账本继续使用本机的来源和组数。
                    val field = k.substringAfterLast(':')
                    if (field in usageDayFields && (k.removeSuffix(field) + "usage_day") in priorUsageDays) continue
                    val sanitized = LocalStore.sanitizeKvValueForLocalOnly(k, kv.optString(k, ""))
                    db.insertWithOnConflict("kv", null, ContentValues().apply {
                        put("k", k); put("v", sanitized)
                    }, if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
                }
            }

            val wantedActive = backup.optString("activeChild", LocalStore.DEFAULT_CHILD)
            if (replace && childExists(wantedActive)) {
                rawSet("active_child", wantedActive)
            }

            backup.optJSONArray("questions")?.let { arr ->
                val conflictMode = !replace
                for (i in 0 until minOf(arr.length(), 50000)) {
                    val q = arr.optJSONObject(i) ?: continue
                    val qid = q.optString("id")
                    if (conflictMode && qid.isNotEmpty() && questionExists(qid)) continue
                    val (cleaned, stripped) = sanitizeBackupQuestionMedia(q, mediaDir)
                    strippedMedia += stripped
                    try {
                        upsertQuestion(cleaned)
                    } catch (e: IllegalArgumentException) {
                        skippedQuestions++
                        Diag.warn("Store", "backup restore skipped invalid question id=$qid: ${e.message}")
                    }
                }
            }

            val conflictPolicy = if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
            backup.optJSONArray("questionStats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val qid = s.optString("qid").takeIf { it.isNotEmpty() } ?: continue
                    db.insertWithOnConflict("question_stats", null, ContentValues().apply {
                        put("qid", qid)
                        put("child", s.optString("child", LocalStore.DEFAULT_CHILD))
                        put("presented", s.optInt("presented", 0).coerceAtLeast(0))
                        put("answered", s.optInt("answered", 0).coerceAtLeast(0))
                        put("correct", s.optInt("correct", 0).coerceAtLeast(0))
                        put("last_at", s.optLong("lastAt", 0L))
                        put("last_correct", s.optInt("lastCorrect", -1).coerceIn(-1, 1))
                    }, conflictPolicy)
                }
            }

            backup.optJSONArray("questionProgress")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val qid = p.optString("qid").takeIf { it.isNotEmpty() } ?: continue
                    db.insertWithOnConflict("question_progress", null, ContentValues().apply {
                        put("qid", qid)
                        put("child", p.optString("child", LocalStore.DEFAULT_CHILD))
                        put("repetitions", p.optInt("repetitions", 0).coerceAtLeast(0))
                        put("interval_days", p.optLong("intervalDays", 0L).coerceAtLeast(0L))
                        put("ease", p.optDouble("ease", 2.5).takeIf { it.isFinite() }?.coerceAtLeast(1.3) ?: 2.5)
                        put("due_at", p.optLong("dueAt", 0L))
                        put("updated_at", p.optLong("updatedAt", 0L))
                    }, conflictPolicy)
                }
            }

            backup.optJSONArray("events")?.let { arr ->
                // 导出是 DESC 顺序，倒序插回保持自增 id 与时间顺序一致
                val dayEnds = mutableMapOf<String, Long>()
                for (i in minOf(arr.length(), 5000) - 1 downTo 0) {
                    val ev = arr.optJSONObject(i) ?: continue
                    val type = ev.optString("type").takeIf { it.isNotEmpty() } ?: continue
                    val payloadObj = ev.optJSONObject("payload")
                    val payloadStr = payloadObj?.toString() ?: ev.optString("payload", "{}")
                    val ts = ev.optLong("ts", System.currentTimeMillis())
                    val child = ev.optString("child", LocalStore.DEFAULT_CHILD)
                    if (!replace) {
                        val dup = db.rawQuery(
                            "SELECT 1 FROM events WHERE type=? AND ts=? AND child=? LIMIT 1",
                            arrayOf(type, ts.toString(), child)
                        ).use { it.moveToFirst() }
                        if (dup) continue
                    }
                    db.execSQL("INSERT INTO events(type,payload,ts,child) VALUES(?,?,?,?)", arrayOf<Any>(type, payloadStr, ts, child))
                    if (type == "quiz_pass") {
                        val day = priorUsageDays[scoped("usage_day", child)] ?: continue
                        val roundsKey = scoped("usage_day_rounds", child)
                        val rounds = rawGet(roundsKey).toIntOrNull() ?: continue
                        val end = dayEnds.getOrPut(child) { nextDayStart(day, child) }
                        if (ts in day until end) rawSet(roundsKey, (rounds + 1).toString())
                    }
                }
            }

            backup.optJSONArray("reviews")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 200)) {
                    val r = JSONObject((arr.optJSONObject(i) ?: continue).toString())
                    val id = r.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val nonce = r.optJSONObject("input")?.optString("nonce")?.takeIf { it.isNotEmpty() } ?: continue
                    if (!replace && review(id) != null) continue
                    r.optJSONObject("input")?.let { input ->
                        val d = input.optString("drawing").trim()
                        if (d.startsWith("data:image/", ignoreCase = true)) {
                            val saved = migrateDataUrlField(d)
                            if (saved.isEmpty()) input.remove("drawing") else input.put("drawing", saved)
                        }
                    }
                    r.optJSONObject("question")?.let { q -> migrateQuestionObjectMedia(q) }
                    db.insertWithOnConflict("reviews", null, ContentValues().apply {
                        put("id", id)
                        put("nonce", nonce)
                        put("state", r.optString("state", "pending"))
                        put("payload", r.toString())
                        put("ts", r.optLong("ts", System.currentTimeMillis()))
                    }, conflictPolicy)
                }
            }

            backup.optJSONObject("grading")?.let { g ->
                if (replace || rawGet("grading_settings").isEmpty()) {
                    val next = JSONObject(g.toString())
                    for (uiKey in listOf("hasKey", "chatUrl", "providers", "clearKey")) next.remove(uiKey)
                    if (next.optString("apiKey").isBlank() && existingGrading.optString("apiKey").isNotBlank()) {
                        next.put("apiKey", existingGrading.getString("apiKey"))
                    }
                    rawSet("grading_settings", next.toString())
                }
            }

            backup.optJSONObject("speech")?.let { s ->
                if (replace || rawGet("speech_settings").isEmpty()) {
                    val next = JSONObject(s.toString())
                    for (secret in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
                        next.remove(secret + "Set")
                        if (next.optString(secret).isBlank() && existingSpeech.optString(secret).isNotBlank()) {
                            next.put(secret, existingSpeech.getString(secret))
                        }
                    }
                    rawSet("speech_settings", next.toString())
                }
            }

            backup.optJSONObject("bankAutomation")?.let { ba ->
                if (replace || rawGet("bank_automation").isEmpty()) {
                    val next = LocalStore.sanitizeBankAutomationForLocalOnly(ba)
                    rawSet("bank_automation", next.toString())
                }
            }

            if (replace) {
                val devName = backup.optString("deviceName").trim()
                if (devName.length in 1..32) Prefs.setDeviceName(appContext, devName)
            }

            configCache.clear()
            cachedChild = null
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    if (strippedMedia > 0) Diag.warn("Store", "backup restore sanitized $strippedMedia invalid media ref(s)")
    if (!db.inTransaction()) pruneMedia()
    val totalQ = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val totalEv = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    val totalRev = readableDatabase.rawQuery("SELECT COUNT(*) FROM reviews", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    return JSONObject()
        .put("ok", true)
        .put("children", children().length())
        .put("questions", totalQ)
        .put("events", totalEv)
        .put("reviews", totalRev)
        .put("skippedQuestions", skippedQuestions)
        .put("strippedMedia", strippedMedia)
}
