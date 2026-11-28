package com.studyontv.tv

import org.json.JSONObject

/**
 * 朗读与语音识别配置读写、合法性校验以及 DeepSeek 密钥复用与清理。
 */
internal object SpeechSettings {
    private val OVERRIDES get() = SpeechReader.LANGUAGES + listOf("system")

    fun settings(store: LocalStore): JSONObject {
        val raw = JSONObject(store.get("speech_settings", "{}"))
        val ownDeepseek = raw.optString("deepseekKey").trim()
        val effectiveDeepseek = deepseekKey(raw, store)
        return raw.apply {
            if (!has("mode")) put("mode", "auto")
            if (!has("readLanguage")) put("readLanguage", "")
            if (!has("edgeVoice")) put("edgeVoice", "")
            if (!has("speechRate")) put("speechRate", 1.0)
            if (!has("deepseekVoice")) put("deepseekVoice", "stella")
            if (!has("recognizeMode")) put("recognizeMode", "system")
            if (optString("readScope") !in SpeechReader.READ_SCOPES) put("readScope", "question")
            for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
                put(key + "Set", optString(key).isNotEmpty())
                remove(key)
            }
            put("deepseekInherited", ownDeepseek.isEmpty() && effectiveDeepseek.isNotEmpty())
            put("deepseekReady", effectiveDeepseek.isNotEmpty())
        }
    }

    fun save(store: LocalStore, input: JSONObject, onSaved: () -> Unit): JSONObject {
        val next = JSONObject(store.get("speech_settings", "{}"))
        val mode = input.optString("mode", "system")
        require(mode in listOf("auto", "system", "edge", "deepseek", "azure", "aliyun")) { "未知朗读通路" }
        next.put("mode", mode).put("autoRead", input.optBoolean("autoRead"))
        if (input.has("readScope")) {
            val scope = input.optString("readScope", "question")
            require(scope in SpeechReader.READ_SCOPES) { "未知朗读内容" }
            next.put("readScope", scope)
        }
        if (input.has("recognizeMode")) {
            val recognize = input.optString("recognizeMode", "system")
            require(recognize in SpeechReader.RECOGNIZE_MODES) { "未知语音识别通路" }
            next.put("recognizeMode", recognize)
        }
        if (input.has("deepseekVoice")) {
            val voice = input.optString("deepseekVoice").trim().ifEmpty { "stella" }
            require(voice in SpeechReader.DEEPSEEK_VOICES) { "不支持的 DeepSeek 音色" }
            next.put("deepseekVoice", voice)
        }
        if (input.has("readLanguage")) {
            val readLanguage = input.optString("readLanguage").trim()
            require(readLanguage in OVERRIDES) { "不支持的朗读语言" }
            next.put("readLanguage", readLanguage)
        }
        if (input.has("edgeVoice")) {
            val edgeVoice = input.optString("edgeVoice").trim()
            require(edgeVoice.isEmpty() || edgeVoice in EdgeTts.VOICES) { "不支持的 Edge 音色" }
            next.put("edgeVoice", edgeVoice)
        }
        if (input.has("speechRate")) {
            val rate = input.optDouble("speechRate", 1.0)
            require(rate.isFinite() && rate in 0.7..1.5) { "朗读语速需在 0.7 到 1.5 之间" }
            next.put("speechRate", rate)
        }
        for (key in listOf("azureRegion", "azureVoice", "aliyunAppKey", "aliyunAccessKeyId", "aliyunVoice")) {
            if (input.has(key)) {
                require(input.getString(key).length <= 200)
                next.put(key, input.getString(key).trim())
            }
        }
        require(next.optString("azureRegion").matches(Regex("[a-z0-9-]*"))) { "Azure 区域格式错误" }
        applySpeechKeyMutations(next, input)
        // 在朗读里填写 DeepSeek Key 时，若「模型 API」选的是 DeepSeek 且还没填 Key，自动同步过去，避免家长填两遍
        val newDeepseekKey = input.optString("deepseekKey").trim()
        if (newDeepseekKey.isNotEmpty()) {
            try {
                val grading = JSONObject(store.get("grading_settings", "{}"))
                val provider = grading.optString("provider", "deepseek")
                if (provider == "deepseek" && grading.optString("apiKey").trim().isEmpty()) {
                    grading.put("provider", "deepseek").put("apiKey", newDeepseekKey)
                    store.set("grading_settings", grading.toString())
                }
            } catch (_: Exception) { }
        }
        store.set("speech_settings", next.toString())
        onSaved()
        return settings(store)
    }

    /** 先清空旧密钥，再写入本次显式提交的新密钥，确保 clearKeys 与新密钥同传时幂等一致。 */
    fun applySpeechKeyMutations(next: JSONObject, input: JSONObject) {
        if (input.optBoolean("clearKeys")) {
            next.remove("azureKey")
            next.remove("aliyunSecret")
            next.remove("deepseekKey")
        }
        for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
            if (input.has(key) && input.optString(key).isNotBlank()) {
                val raw = input.getString(key)
                require(raw.length <= 500)
                next.put(key, raw.trim())
            }
        }
    }

    fun resolveDeepseekKey(config: JSONObject, gradingJsonProvider: () -> String): String {
        val own = config.optString("deepseekKey").trim()
        if (own.isNotEmpty()) return own
        if (!config.optBoolean("reuseGradingKey", true)) return ""
        return try {
            val grading = JSONObject(gradingJsonProvider())
            val provider = grading.optString("provider", "deepseek").trim()
            val endpoint = grading.optString("endpoint", "https://api.deepseek.com/v1").trim()
            if (provider == "deepseek" || endpoint.contains("api.deepseek.com", ignoreCase = true)) {
                grading.optString("apiKey").trim()
            } else ""
        } catch (_: Exception) { "" }
    }

    fun deepseekKey(config: JSONObject, store: LocalStore? = null): String =
        resolveDeepseekKey(config) { (store ?: DeviceRuntime.store).get("grading_settings", "{}") }

    fun resolveRecognizeKey(localOnly: Boolean, speechJson: String, gradingJsonProvider: () -> String): String {
        if (localOnly) return ""
        val config = try { JSONObject(speechJson) } catch (_: Exception) { JSONObject() }
        return if (config.optString("recognizeMode", "system") == "system") "" else resolveDeepseekKey(config, gradingJsonProvider)
    }

    fun recognizeKey(store: LocalStore): String =
        resolveRecognizeKey(LocalOnlyGuard.enabled, store.get("speech_settings", "{}")) { store.get("grading_settings", "{}") }
}
