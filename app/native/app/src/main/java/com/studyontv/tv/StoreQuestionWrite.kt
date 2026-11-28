package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun LocalStore.upsertQuestionImpl(item: JSONObject): String {
    val stem = item.optString("q").trim()
    require(stem.isNotEmpty() && stem.length <= 10000) { "请填写题干" }
    val type = item.optString("type", "single")
    require(type in listOf("single", "multiple", "boolean", "text", "subjective")) { "不支持此题型" }
    val options = if (type == "boolean") JSONArray(listOf("正确", "错误")) else item.optJSONArray("a") ?: JSONArray()
    val rawOptionImages = item.optJSONArray("optionImages")
    val normalizedOptionImages: JSONArray? = if (rawOptionImages != null) {
        require(rawOptionImages.length() <= 8) { "选项配图不能超过 8 张" }
        JSONArray().also { arr ->
            for (i in 0 until rawOptionImages.length()) {
                val img = rawOptionImages.optString(i).trim()
                arr.put(if (img.isEmpty()) "" else MediaStore.normalizeImageRef(mediaDir, img, label = "选项 ${'A' + i} 配图"))
            }
        }
    } else null
    if (type != "text" && type != "subjective") {
        require(options.length() in 2..8) { "需要 2–8 个选项" }
        for (i in 0 until options.length()) {
            val optImg = normalizedOptionImages?.optString(i).orEmpty()
            require(options.getString(i).isNotBlank() || optImg.isNotEmpty()) { "选项不能为空" }
        }
    }
    val indices = if (type == "multiple") item.optJSONArray("correctIndices") ?: JSONArray()
        else JSONArray().put(item.optInt("k", -1))
    if (type != "text" && type != "subjective") {
        require(indices.length() > 0) { "请选择正确答案" }
        for (i in 0 until indices.length()) require(indices.getInt(i) in 0 until options.length()) { "答案序号超出选项范围" }
    } else if (type == "text") {
        val answers = item.optJSONArray("answers") ?: JSONArray()
        require(answers.length() > 0) { "填空题需要至少一个可接受的答案" }
        for (i in 0 until answers.length()) require(answers.getString(i).isNotBlank()) { "答案不能为空" }
    }
    val difficulty = item.optInt("difficulty", 2)
    require(difficulty in 1..5) { "难度需要为 1–5 星" }
    val id = item.optString("id").ifEmpty { "q_" + UUID.randomUUID().toString() }
    val details = JSONObject(item.toString()).put("type", type)
    details.remove("stats")
    if (normalizedOptionImages != null) details.put("optionImages", normalizedOptionImages) else details.remove("optionImages")
    val bank = item.optString("bank", "默认题库").trim()
    require(bank.isNotEmpty() && bank.length <= 64) { "题库名称需要 1–64 个字" }
    details.put("bank", bank)
    val rawImage = item.optString("image").trim()
    details.put("image", if (rawImage.isEmpty()) "" else MediaStore.normalizeImageRef(mediaDir, rawImage, label = "图片"))
    val rawAudio = item.optString("audio").trim()
    require(rawAudio.isEmpty() || ((rawAudio.startsWith("https://") || rawAudio.startsWith("http://")) && rawAudio.length <= 2048)) { "图片或音频地址无效" }
    details.put("audio", rawAudio)
    // 听力题：listen 只交给朗读，屏幕上不显示，与音频地址二选一
    val listen = item.optString("listen").trim()
    require(listen.isEmpty() || listen.length <= 1000) { "听力文本不能超过 1000 字" }
    require(listen.isEmpty() || rawAudio.isEmpty()) { "听力文本和音频地址只能二选一" }
    details.put("listen", listen)
    if (listen.isEmpty()) {
        details.remove("listenTimes"); details.remove("listenRequired"); details.remove("listenRate")
    } else {
        details.put("listenTimes", item.optInt("listenTimes", 5).coerceIn(1, 5))
        details.put("listenRequired", item.optBoolean("listenRequired", false))
        val listenRate = item.optDouble("listenRate", -1.0)
        if (listenRate.isFinite() && listenRate in 0.7..1.5) details.put("listenRate", listenRate) else details.remove("listenRate")
    }
    if (item.has("images")) {
        val rawImages = item.optJSONArray("images") ?: JSONArray()
        require(rawImages.length() <= 4) { "每道题最多支持 4 张配图" }
        val validImages = JSONArray()
        for (i in 0 until rawImages.length()) {
            val value = rawImages.optString(i).trim()
            if (value.isEmpty()) continue
            validImages.put(MediaStore.normalizeImageRef(mediaDir, value, label = "第 ${i + 1} 张配图"))
        }
        details.put("images", validImages)
        if (details.optString("image").isEmpty() && validImages.length() > 0) {
            details.put("image", validImages.getString(0))
        }
    }
    val language = item.optString("language", "zh-CN")
    require(language in SpeechReader.LANGUAGES) { "朗读/识别语言需要为中文、粤语或英文，留空表示跟随系统" }
    details.put("language", language)
    val enabled = if (!item.has("enabled") || item.optBoolean("enabled", true)) 1 else 0
    val createdAt = item.optLong("createdAt", System.currentTimeMillis())
    writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
        put("id", id); put("subject", item.optString("subject", "常识"))
        put("level", item.optString("level", "")); put("difficulty", difficulty)
        put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
        put("tip", item.optString("tip")); put("enabled", enabled); put("created_at", createdAt)
        put("details", details.toString())
        put("bank", bank); put("type", type)
    }, SQLiteDatabase.CONFLICT_REPLACE)
    return id
}

/** 题干去重用的归一化：忽略空白、常见标点和大小写，避免同一道题反复导入 */
private fun stemKey(stem: String): String =
    stem.lowercase().replace(Regex("[\\s，。？?！!、；;：:（）()\"'“”‘’《》<>]"), "")

internal fun LocalStore.importQuestionsImpl(items: JSONArray, skipDuplicates: Boolean): JSONObject {
    require(items.length() in 1..50000) { "每次导入 1–50000 道题" }
    val db = writableDatabase
    val ids = HashSet<String>()
    val stems = HashSet<String>()
    if (skipDuplicates) {
        db.rawQuery("SELECT id, stem FROM questions", null).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0))
                stems.add(stemKey(cursor.getString(1) ?: ""))
            }
        }
    }
    var added = 0
    db.beginTransaction()
    try {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: throw IllegalArgumentException("第 ${i + 1} 题：不是一条题目对象")
            if (skipDuplicates) {
                val id = item.optString("id")
                val key = stemKey(item.optString("q"))
                val replaces = id.isNotEmpty() && id in ids
                if (!replaces && key.isNotEmpty() && !stems.add(key)) continue
                if (id.isNotEmpty()) ids.add(id)
            }
            try { upsertQuestion(item) } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${i + 1} 题：${e.message}")
            }
            added++
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    return JSONObject().put("added", added).put("skipped", items.length() - added)
}

internal fun LocalStore.clearQuestionsImpl(): Int {
    val db = writableDatabase
    val deleted: Int
    db.beginTransaction()
    try {
        deleted = db.delete("questions", null, null)
        db.delete("question_progress", null, null)
        db.delete("question_stats", null, null)
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    if (!db.inTransaction()) pruneMedia()
    return deleted
}

internal fun LocalStore.deleteQuestionsImpl(ids: List<String>): Int {
    if (ids.isEmpty()) return 0
    val db = writableDatabase
    var count = 0
    db.beginTransaction()
    try {
        ids.forEach {
            count += db.delete("questions", "id=?", arrayOf(it))
            db.delete("question_progress", "qid=?", arrayOf(it))
            db.delete("question_stats", "qid=?", arrayOf(it))
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    if (!db.inTransaction()) pruneMedia()
    return count
}

internal fun LocalStore.setEnabledImpl(ids: List<String>, enabled: Boolean): Int {
    val db = writableDatabase; var count = 0
    db.beginTransaction()
    try {
        ids.forEach { id ->
            count += db.update("questions", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
        }
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
    return count
}

internal fun LocalStore.saveReviewImpl(value: JSONObject) {
    writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
        put("id", value.getString("id")); put("nonce", value.getJSONObject("input").getString("nonce"))
        put("state", value.optString("state", "pending")); put("payload", value.toString()); put("ts", value.getLong("ts"))
    }, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun LocalStore.noteReviewImpl(value: JSONObject) {
    writableDatabase.update("reviews", ContentValues().apply { put("payload", value.toString()) },
        "id=? AND state='pending'", arrayOf(value.getString("id")))
}

internal fun LocalStore.reviewImpl(id: String): JSONObject? =
    readableDatabase.rawQuery("SELECT payload FROM reviews WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

internal fun LocalStore.reviewsImpl(state: String, child: String?): JSONArray {
    val out = JSONArray()
    val filter = if (state == "all") "" else "WHERE state=?"
    readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
        while (it.moveToNext()) {
            val obj = JSONObject(it.getString(0))
            if (child == null || obj.optString("child", LocalStore.DEFAULT_CHILD) == child) out.put(obj)
        }
    }
    return out
}
