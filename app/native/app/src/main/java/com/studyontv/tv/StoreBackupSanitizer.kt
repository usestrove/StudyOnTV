package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun isEphemeralKvKeyImpl(key: String): Boolean {
    val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
    return base in LocalStore.EPHEMERAL_KV_KEYS
}

internal fun normalizeSyncScopesImpl(scopes: Collection<String>): Set<String> =
    scopes.mapTo(LinkedHashSet()) { it.trim().lowercase(Locale.US) }.filterTo(LinkedHashSet()) { it in LocalStore.SYNC_SCOPES }

/** 仅允许同步学习计划规则（`config` 与 `child:<id>:config`），严禁同步单机运行态计时、密码或设备名。 */
internal fun isConfigSyncKvKeyImpl(key: String): Boolean {
    if (key == "config") return true
    if (key.startsWith("child:") && key.endsWith(":config")) {
        val childId = key.removePrefix("child:").removeSuffix(":config")
        return childId.isNotEmpty() && LocalStore.isValidChildId(childId)
    }
    return false
}

/**
 * 纯函数：从备份/源对象按 `scopes` 白名单精准裁剪同步载荷。
 */
internal fun buildScopedSyncPayloadImpl(source: JSONObject, scopes: Set<String>): JSONObject {
    val validScopes = normalizeSyncScopesImpl(scopes)
    require(validScopes.isNotEmpty()) { "请至少选择一项需要同步的内容" }
    val out = JSONObject()
        .put("format", "studyontv-backup")
        .put("version", source.optInt("version", 1))
        .put("exportedAt", source.optLong("exportedAt", System.currentTimeMillis()))
        .put("appVersion", source.optString("appVersion", BuildConfig.VERSION_NAME))
        .put("scopes", JSONArray(validScopes.sorted()))
        .put("includeQuestions", "questions" in validScopes)
    if ("config" in validScopes) {
        source.optJSONArray("children")?.let { out.put("children", it) }
        val filteredKv = JSONObject()
        source.optJSONObject("kv")?.let { kv ->
            val keys = kv.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (isConfigSyncKvKeyImpl(k)) {
                    filteredKv.put(k, kv.optString(k, ""))
                }
            }
        }
        out.put("kv", filteredKv)
    }
    if ("questions" in validScopes) {
        out.put("questions", source.optJSONArray("questions") ?: JSONArray())
    }
    if ("speech" in validScopes) {
        source.optJSONObject("speech")?.let { out.put("speech", it) }
    }
    if ("grading" in validScopes) {
        source.optJSONObject("grading")?.let { out.put("grading", it) }
    }
    return out
}

/** EU Local-Only 模式下清洗单个备份 kv 项（剥离 config 中的公网公益视频与 bank_market_sources 中的公网源）。 */
internal fun sanitizeKvValueForLocalOnlyImpl(key: String, value: String, localOnly: Boolean): String {
    if (!localOnly) return value
    val base = if (key.startsWith("child:")) key.substringAfterLast(':') else key
    return when (base) {
        "config" -> try {
            val cfg = JSONObject(value)
            val videos = cfg.optJSONArray("educationalVideos")
            if (videos != null) {
                val kept = JSONArray()
                for (i in 0 until videos.length()) {
                    val raw = videos.optString(i).trim()
                    if (raw.isNotEmpty() && LocalOnlyGuard.isLocalUrl(raw)) kept.put(raw)
                }
                cfg.put("educationalVideos", kept)
                if (kept.length() == 0 && cfg.optString("learningContent", "quiz") != "quiz") {
                    cfg.put("learningContent", "quiz")
                }
            }
            cfg.toString()
        } catch (_: Exception) { value }
        "bank_market_sources" -> try {
            val arr = JSONArray(value)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
            }
            kept.toString()
        } catch (_: Exception) { value }
        "bank_automation" -> try {
            sanitizeBankAutomationForLocalOnlyImpl(JSONObject(value), localOnly).toString()
        } catch (_: Exception) { value }
        else -> value
    }
}

/** EU Local-Only 模式下清洗备份中的 bankAutomation.sources 公网远程题库源。 */
internal fun sanitizeBankAutomationForLocalOnlyImpl(raw: JSONObject, localOnly: Boolean): JSONObject {
    val next = JSONObject(raw.toString())
    next.remove("running")
    next.remove("status")
    if (!localOnly) return next
    val sources = next.optJSONArray("sources")
    if (sources != null) {
        val kept = JSONArray()
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (url.isNotEmpty() && LocalOnlyGuard.isLocalUrl(url)) kept.put(item)
        }
        next.put("sources", kept)
    }
    return next
}
