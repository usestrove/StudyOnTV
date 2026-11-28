package com.studyontv.tv

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 逐行迁移历史库里的内联 `data:image/`：
 * 用 `LIKE '%data:image%'` 跳过纯文字行，且每次 `LIMIT 1` 读取单行，避免多张大图撑爆 2MB CursorWindow。
 */
internal fun LocalStore.migrateInlineMedia(db: SQLiteDatabase) {
    val updateQ = db.compileStatement("UPDATE questions SET details=? WHERE id=?")
    try {
        var lastRow = -1L
        while (true) {
            var rowId = -1L
            var id = ""
            var raw = ""
            db.rawQuery(
                "SELECT rowid,id,details FROM questions WHERE rowid>? AND details LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                arrayOf(lastRow.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    rowId = c.getLong(0)
                    id = c.getString(1) ?: ""
                    raw = c.getString(2) ?: ""
                }
            }
            if (rowId < 0) break
            lastRow = rowId
            val updated = migrateQuestionDetailsJson(raw)
            if (updated != null && updated != raw) {
                updateQ.clearBindings()
                updateQ.bindString(1, updated)
                updateQ.bindString(2, id)
                updateQ.executeUpdateDelete()
            }
        }
    } finally {
        updateQ.close()
    }

    val updateR = db.compileStatement("UPDATE reviews SET payload=? WHERE id=?")
    try {
        var lastRow = -1L
        while (true) {
            var rowId = -1L
            var id = ""
            var raw = ""
            db.rawQuery(
                "SELECT rowid,id,payload FROM reviews WHERE rowid>? AND payload LIKE '%data:image%' ORDER BY rowid LIMIT 1",
                arrayOf(lastRow.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    rowId = c.getLong(0)
                    id = c.getString(1) ?: ""
                    raw = c.getString(2) ?: ""
                }
            }
            if (rowId < 0) break
            lastRow = rowId
            val updated = migrateReviewPayloadJson(raw)
            if (updated != null && updated != raw) {
                updateR.clearBindings()
                updateR.bindString(1, updated)
                updateR.bindString(2, id)
                updateR.executeUpdateDelete()
            }
        }
    } finally {
        updateR.close()
    }
}

internal fun LocalStore.migrateDataUrlField(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("data:image/", ignoreCase = true)) return raw
    return try {
        MediaStore.saveDataUrl(mediaDir, trimmed, 2_000_000)
    } catch (_: Exception) {
        // 历史脏数据无法解码时清空坏图引用，绝不让数据库升级抛异常卡死启动
        ""
    }
}

internal fun LocalStore.migrateQuestionObjectMedia(obj: JSONObject): Boolean {
    var changed = false
    val image = obj.optString("image")
    if (image.startsWith("data:image/", ignoreCase = true)) {
        obj.put("image", migrateDataUrlField(image))
        changed = true
    }
    val images = obj.optJSONArray("images")
    if (images != null) {
        val next = JSONArray()
        var arrChanged = false
        for (i in 0 until images.length()) {
            val v = images.optString(i)
            val m = migrateDataUrlField(v)
            if (m != v) arrChanged = true
            if (m.isNotEmpty()) next.put(m)
        }
        if (arrChanged) {
            obj.put("images", next)
            changed = true
        }
    }
    val optImgs = obj.optJSONArray("optionImages")
    if (optImgs != null) {
        val next = JSONArray()
        var arrChanged = false
        for (i in 0 until optImgs.length()) {
            val v = optImgs.optString(i)
            val m = migrateDataUrlField(v)
            if (m != v) arrChanged = true
            next.put(m)
        }
        if (arrChanged) {
            obj.put("optionImages", next)
            changed = true
        }
    }
    return changed
}

internal fun LocalStore.migrateQuestionDetailsJson(raw: String): String? = try {
    val obj = JSONObject(raw)
    if (migrateQuestionObjectMedia(obj)) obj.toString() else null
} catch (_: Exception) { null }

internal fun LocalStore.migrateReviewPayloadJson(raw: String): String? = try {
    val obj = JSONObject(raw)
    var changed = false
    obj.optJSONObject("input")?.let { input ->
        val drawing = input.optString("drawing")
        if (drawing.startsWith("data:image/", ignoreCase = true)) {
            val migrated = migrateDataUrlField(drawing)
            if (migrated.isEmpty()) input.remove("drawing") else input.put("drawing", migrated)
            changed = true
        }
    }
    obj.optJSONObject("question")?.let { q ->
        if (migrateQuestionObjectMedia(q)) changed = true
    }
    if (changed) obj.toString() else null
} catch (_: Exception) { null }

/** 扫描所有仍在被引用的本地媒体文件并删除孤儿图片。只应在数据库最外层事务提交后调用。 */
internal fun LocalStore.pruneMediaImpl(): Int {
    if (!mediaDir.exists()) return 0
    val referenced = HashSet<String>()
    val db = readableDatabase
    db.rawQuery("SELECT details FROM questions WHERE details LIKE '%media%'", null).use { c ->
        while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
    }
    db.rawQuery("SELECT payload FROM reviews WHERE payload LIKE '%media%'", null).use { c ->
        while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
    }
    db.rawQuery("SELECT v FROM kv WHERE v LIKE '%media%'", null).use { c ->
        while (c.moveToNext()) c.getString(0)?.let { MediaStore.collectMediaNamesFromJson(it, referenced) }
    }
    return MediaStore.pruneUnreferenced(mediaDir, referenced)
}
