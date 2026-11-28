package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/** 整机备份导出：含多孩子、规则、题库内联媒体与学习记录，自动脱敏密钥。 */
internal fun LocalStore.exportBackupImpl(includeQuestions: Boolean): JSONObject {
    ensureDefaultChild()
    val kvObj = JSONObject()
    readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c ->
        while (c.moveToNext()) {
            val k = c.getString(0) ?: continue
            if (k in LocalStore.SECRET_KV_KEYS || LocalStore.isEphemeralKvKey(k)) continue
            kvObj.put(k, c.getString(1) ?: "")
        }
    }
    val exportedQuestions = JSONArray()
    if (includeQuestions) {
        val all = readQuestions(
            "SELECT questions.*,0 AS presented,0 AS answered_count,0 AS correct_count,-1 AS last_correct FROM questions ORDER BY rowid ASC",
            emptyArray()
        )
        for (i in 0 until all.length()) {
            val q = MediaStore.inlineQuestionMedia(mediaDir, all.getJSONObject(i))
            q.remove("stats")
            exportedQuestions.put(q)
        }
    }
    val statsArr = JSONArray()
    readableDatabase.rawQuery("SELECT qid,child,presented,answered,correct,last_at,last_correct FROM question_stats", null).use { c ->
        while (c.moveToNext()) {
            statsArr.put(
                JSONObject()
                    .put("qid", c.getString(0)).put("child", c.getString(1) ?: "")
                    .put("presented", c.getInt(2)).put("answered", c.getInt(3))
                    .put("correct", c.getInt(4)).put("lastAt", c.getLong(5))
                    .put("lastCorrect", c.getInt(6))
            )
        }
    }
    val progressArr = JSONArray()
    readableDatabase.rawQuery("SELECT qid,child,repetitions,interval_days,ease,due_at,updated_at FROM question_progress", null).use { c ->
        while (c.moveToNext()) {
            progressArr.put(
                JSONObject()
                    .put("qid", c.getString(0)).put("child", c.getString(1) ?: "")
                    .put("repetitions", c.getInt(2)).put("intervalDays", c.getLong(3))
                    .put("ease", c.getDouble(4)).put("dueAt", c.getLong(5))
                    .put("updatedAt", c.getLong(6))
            )
        }
    }
    val eventsArr = JSONArray()
    readableDatabase.rawQuery("SELECT type,payload,ts,child FROM events ORDER BY id DESC LIMIT 5000", null).use { c ->
        while (c.moveToNext()) {
            val rawPayload = c.getString(1) ?: "{}"
            val parsedPayload: Any = try { JSONObject(rawPayload) } catch (_: Exception) { rawPayload }
            eventsArr.put(
                JSONObject()
                    .put("type", c.getString(0) ?: "")
                    .put("payload", parsedPayload)
                    .put("ts", c.getLong(2))
                    .put("child", c.getString(3) ?: "")
            )
        }
    }
    val reviewsArr = JSONArray()
    readableDatabase.rawQuery("SELECT payload FROM reviews ORDER BY ts DESC LIMIT 200", null).use { c ->
        while (c.moveToNext()) {
            val r = try { JSONObject(c.getString(0) ?: "{}") } catch (_: Exception) { continue }
            r.optJSONObject("input")?.let { input ->
                val d = input.optString("drawing")
                if (d.isNotEmpty()) {
                    val inlined = MediaStore.toDataUrl(mediaDir, d)
                    if (inlined.isEmpty()) input.remove("drawing") else input.put("drawing", inlined)
                }
            }
            r.optJSONObject("question")?.let { q ->
                r.put("question", MediaStore.inlineQuestionMedia(mediaDir, q))
            }
            reviewsArr.put(r)
        }
    }
    val bankAuto = BankAutomation.settings(this).apply { remove("running") }
    return JSONObject()
        .put("format", "studyontv-backup")
        .put("version", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("deviceName", Prefs.deviceName(appContext))
        .put("includeQuestions", includeQuestions)
        .put("activeChild", activeChild())
        .put("children", children())
        .put("kv", kvObj)
        .put("questions", exportedQuestions)
        .put("questionStats", statsArr)
        .put("questionProgress", progressArr)
        .put("events", eventsArr)
        .put("reviews", reviewsArr)
        .put("speech", SpeechReader.settings(this))
        .put("grading", GradingService.settings(this, includeKey = false))
        .put("bankAutomation", bankAuto)
}

internal fun LocalStore.exportScopedSyncImpl(scopes: Set<String>): JSONObject {
    val validScopes = LocalStore.normalizeSyncScopes(scopes)
    require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
    ensureDefaultChild()
    val source = JSONObject()
        .put("format", "studyontv-backup")
        .put("version", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("appVersion", BuildConfig.VERSION_NAME)
    if ("config" in validScopes) {
        source.put("children", children())
        val kvObj = JSONObject()
        readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: continue
                if (!LocalStore.isConfigSyncKvKey(k)) continue
                kvObj.put(k, c.getString(1) ?: "")
            }
        }
        source.put("kv", kvObj)
    }
    if ("questions" in validScopes) {
        val exportedQuestions = JSONArray()
        val all = readQuestions(
            "SELECT questions.*,0 AS presented,0 AS answered_count,0 AS correct_count,-1 AS last_correct FROM questions ORDER BY rowid ASC",
            emptyArray()
        )
        for (i in 0 until all.length()) {
            val q = MediaStore.inlineQuestionMedia(mediaDir, all.getJSONObject(i))
            q.remove("stats")
            exportedQuestions.put(q)
        }
        source.put("questions", exportedQuestions)
    }
    if ("speech" in validScopes) {
        source.put("speech", SpeechReader.settings(this))
    }
    if ("grading" in validScopes) {
        source.put("grading", GradingService.settings(this, includeKey = false))
    }
    return LocalStore.buildScopedSyncPayload(source, validScopes)
}
