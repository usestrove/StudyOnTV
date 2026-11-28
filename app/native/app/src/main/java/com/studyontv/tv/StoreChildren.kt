package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private val childIdPattern = Regex("^[A-Za-z0-9_-]{1,32}$")
private val childPinRandom = SecureRandom()

internal fun isValidChildIdImpl(id: String): Boolean =
    id == LocalStore.DEFAULT_CHILD || childIdPattern.matches(id)

/**
 * 归一化孩子切换密码输入：null 保持不变，空串清空，非空校验 1..16 位。
 */
internal fun normalizeChildPinImpl(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    require(trimmed.length in 1..16) { "切换密码需要 1–16 位" }
    return trimmed
}

internal fun generatePinSaltImpl(): String =
    ByteArray(16).also { childPinRandom.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

internal fun hashChildPinImpl(pin: String, salt: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun verifyPinHashImpl(pin: String, salt: String, expectedHash: String): Boolean {
    val candidate = pin.trim()
    if (candidate.isEmpty() || salt.isEmpty() || expectedHash.isEmpty()) return false
    val actual = hashChildPinImpl(candidate, salt)
    return MessageDigest.isEqual(
        actual.toByteArray(Charsets.UTF_8),
        expectedHash.toByteArray(Charsets.UTF_8)
    )
}

/** 对外序列化孩子档案：绝不下发 pin_hash / pin_salt / 原文，仅下发 hasPin。 */
internal fun childSummaryJsonImpl(
    id: String,
    name: String,
    createdAt: Long,
    active: Boolean,
    hasPin: Boolean
): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("createdAt", createdAt)
    .put("active", active)
    .put("hasPin", hasPin)

internal fun LocalStore.childExistsImpl(id: String): Boolean =
    id == LocalStore.DEFAULT_CHILD || readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

internal fun <T> LocalStore.withChildImpl(childId: String, block: () -> T): T {
    val prev = childOverride.get()
    childOverride.set(childId)
    return try {
        block()
    } finally {
        if (prev == null) childOverride.remove() else childOverride.set(prev)
    }
}

internal fun LocalStore.activeChildImpl(): String {
    childOverride.get()?.let { return it }
    cachedChild?.let { return it }
    synchronized(childLock) {
        cachedChild?.let { return it }
        val stored = rawGet("active_child")
        val valid = when {
            stored.isEmpty() -> LocalStore.DEFAULT_CHILD
            readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(stored)).use { it.moveToFirst() } -> stored
            else -> { rawSet("active_child", LocalStore.DEFAULT_CHILD); LocalStore.DEFAULT_CHILD }
        }
        cachedChild = valid
        return valid
    }
}

internal fun LocalStore.childrenImpl(): JSONArray {
    ensureDefaultChild()
    val active = activeChild()
    val out = JSONArray()
    readableDatabase.rawQuery("SELECT id,name,created_at,pin_hash FROM children ORDER BY created_at,id", null).use {
        while (it.moveToNext()) {
            val id = it.getString(0)
            val hasPin = !(it.getString(3).isNullOrEmpty())
            out.put(LocalStore.childSummaryJson(id, it.getString(1), it.getLong(2), id == active, hasPin))
        }
    }
    return out
}

internal fun LocalStore.ensureDefaultChild() {
    val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(LocalStore.DEFAULT_CHILD)).use { it.moveToFirst() }
    if (!exists) writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
        put("id", LocalStore.DEFAULT_CHILD); put("name", "孩子1"); put("created_at", System.currentTimeMillis())
    }, SQLiteDatabase.CONFLICT_IGNORE)
}

private fun LocalStore.validChildName(raw: String, except: String? = null): String {
    val name = raw.trim()
    require(name.length in 1..12) { "孩子名字需要 1–12 个字" }
    val taken = readableDatabase.rawQuery("SELECT id FROM children WHERE name=?", arrayOf(name)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    require(taken == null || taken == except) { "已经有同名的孩子" }
    return name
}

internal fun LocalStore.createChildImpl(raw: String, pin: String?): JSONObject {
    ensureDefaultChild()
    val name = validChildName(raw)
    val normalizedPin = LocalStore.normalizeChildPin(pin)
    val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM children", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    require(count < LocalStore.MAX_CHILDREN) { "最多只能添加 ${LocalStore.MAX_CHILDREN} 个孩子" }
    val id = "c" + UUID.randomUUID().toString().replace("-", "").take(8)
    val salt = if (!normalizedPin.isNullOrEmpty()) LocalStore.generatePinSalt() else ""
    val hash = if (!normalizedPin.isNullOrEmpty()) LocalStore.hashChildPin(normalizedPin, salt) else ""
    // 新孩子沿用当前的规则（时长、时段、奖励…），不需要家长再配一遍；学习进度从零开始
    val inherited = rawGet(scoped("config"))
    writableDatabase.insertWithOnConflict("children", null, ContentValues().apply {
        put("id", id); put("name", name); put("created_at", System.currentTimeMillis())
        put("pin_hash", hash); put("pin_salt", salt)
    }, SQLiteDatabase.CONFLICT_REPLACE)
    if (inherited.isNotEmpty()) rawSet("child:$id:config", inherited)
    return JSONObject().put("id", id).put("name", name).put("hasPin", hash.isNotEmpty())
}

internal fun LocalStore.updateChildImpl(id: String, raw: String, pin: String?): JSONObject {
    ensureDefaultChild()
    val name = validChildName(raw, id)
    val normalizedPin = LocalStore.normalizeChildPin(pin)
    val cv = ContentValues().apply {
        put("name", name)
        when {
            normalizedPin == null -> Unit
            normalizedPin.isEmpty() -> {
                put("pin_hash", "")
                put("pin_salt", "")
            }
            else -> {
                val salt = LocalStore.generatePinSalt()
                put("pin_hash", LocalStore.hashChildPin(normalizedPin, salt))
                put("pin_salt", salt)
            }
        }
    }
    val updated = writableDatabase.update("children", cv, "id=?", arrayOf(id))
    require(updated > 0) { "孩子档案不存在" }
    return JSONObject().put("id", id).put("name", name).put("hasPin", childHasPin(id))
}

internal fun LocalStore.childHasPinImpl(childId: String): Boolean {
    ensureDefaultChild()
    return readableDatabase.rawQuery("SELECT pin_hash FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
        it.moveToFirst() && !(it.getString(0).isNullOrEmpty())
    }
}

internal fun LocalStore.verifyChildPinImpl(childId: String, pin: String): Boolean {
    ensureDefaultChild()
    val candidate = pin.trim()
    if (candidate.isEmpty()) return false
    return readableDatabase.rawQuery("SELECT pin_hash,pin_salt FROM children WHERE id=? LIMIT 1", arrayOf(childId)).use {
        if (!it.moveToFirst()) return false
        LocalStore.verifyPinHash(candidate, it.getString(1).orEmpty(), it.getString(0).orEmpty())
    }
}

/** 删除孩子档案：连同他的学习记录、错题统计、复习进度、主观题批改和孤儿手写图一起清掉，题目本身保留 */
internal fun LocalStore.deleteChildImpl(id: String): Boolean {
    require(id != LocalStore.DEFAULT_CHILD) { "第一个孩子不能删除，可以改名" }
    val db = writableDatabase
    // 统一锁顺序：永远先 childLock、再 SQLite 写事务，避免与 switchChild()/activeChild() 产生 AB-BA 死锁
    synchronized(childLock) {
        db.beginTransaction()
        try {
            db.delete("children", "id=?", arrayOf(id))
            db.delete("kv", "k LIKE ?", arrayOf("child:$id:%"))
            db.delete("events", "child=?", arrayOf(id))
            db.delete("question_progress", "child=?", arrayOf(id))
            db.delete("question_stats", "child=?", arrayOf(id))
            // Android 5+ 自带 SQLite 不支持 JSON_EXTRACT，在 Kotlin 层过滤属于该孩子的批改记录
            val reviewIds = ArrayList<String>()
            db.rawQuery("SELECT id,payload FROM reviews", null).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getString(0) ?: continue
                    val payload = c.getString(1) ?: continue
                    val owner = try { JSONObject(payload).optString("child", LocalStore.DEFAULT_CHILD) } catch (_: Exception) { LocalStore.DEFAULT_CHILD }
                    if (owner == id) reviewIds.add(rid)
                }
            }
            for (rid in reviewIds) db.delete("reviews", "id=?", arrayOf(rid))
            configCache.remove(id)
            if (activeChild() == id) switchChild(LocalStore.DEFAULT_CHILD)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    if (!db.inTransaction()) pruneMedia()
    return true
}

internal fun LocalStore.switchChildImpl(id: String) {
    synchronized(childLock) {
        ensureDefaultChild()
        val exists = readableDatabase.rawQuery("SELECT 1 FROM children WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
        require(exists) { "孩子档案不存在" }
        rawSet("active_child", id)
        cachedChild = id
    }
}
