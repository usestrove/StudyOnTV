package com.studyontv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.Locale

/**
 * Activity 与悬浮层共用同一朗读通路；云端失败时回报原因并回落到系统语音。
 *
 * 回报有两条路：`status()`（桥方法 `speechStatus` 拉取）与 `notice` 回调（推给网页的 SOTV_SPEECH_NOTICE）。
 */
object SpeechReader {
    /** 语音识别白名单：白名单外的题目语言不静默当中文，而是降级到设备默认语言并记诊断（#81） */
    private val RECOGNITION_LANGUAGES = listOf("zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 题目可以标注的朗读语言；"" 表示跟随设备系统语言 */
    val LANGUAGES = listOf("", "zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 语音识别支持的语种（白名单外再降级，供回归测试断言） */
    val whitelist: List<String> get() = RECOGNITION_LANGUAGES
    /** DeepSeek 语音（合成 + 转写）的两种音色 */
    val DEEPSEEK_VOICES = listOf("stella", "tide")
    /** 语音转写通路：system 用系统识别器、deepseek 上传给 DeepSeek、auto 填了 Key 就走 DeepSeek */
    val RECOGNIZE_MODES = listOf("system", "deepseek", "auto")
    /** 自动朗读的内容范围：只读题干、题干和选项、题干选项再加答案 */
    val READ_SCOPES = listOf("question", "questionOptions", "questionOptionsAnswer")

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var generation = 0
    private var system: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    /** 最近一次朗读 / 播放的结果，供 speechStatus 桥与回归测试断言 */
    @Volatile private var last: JSONObject? = null
    @Volatile private var speaking = false
    /** 最近一次朗读活跃时间（elapsedRealtime），避免 MediaPause.enforce 把自家朗读误判成后台视频 */
    @Volatile private var lastSpokeAt = 0L
    /** 桥的回报回调（只在一次朗读 / 播放期间持有，stopNow 释放，避免长期抓住 WebView） */
    private var notice: ((JSONObject) -> Unit)? = null
    private var cachedContext: Context? = null
    @Volatile private var currentText = ""
    @Volatile private var currentLanguage = ""
    /** 单次朗读的语速覆盖：听力题可以按题指定，-1 表示跟随全局 speechRate */
    @Volatile private var currentRate = -1.0
    @Volatile private var lastUtteranceId = ""
    @Volatile private var retriedDefault = false
    @Volatile private var retriedSpeakError = false

    /** 需要直接弹在电视屏幕上的失败：老电视上「点了朗读没声音」最常见的几类 */
    private val TOAST_REASONS = setOf("engine-unavailable", "language-unavailable", "language-check-failed", "utterance-error", "cloud-unavailable")
    private val SENTENCE_ENDS = "。！？!?；;\n"

    internal fun markSpeaking(active: Boolean) {
        speaking = active
        lastSpokeAt = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
    }

    fun settings(store: LocalStore): JSONObject = SpeechSettings.settings(store)

    fun save(store: LocalStore, input: JSONObject): JSONObject =
        SpeechSettings.save(store, input) { CloudSpeechProviders.clearCaches() }

    /**
     * 朗读。`language` 是题目语言；引擎不支持时先回落设备默认语言，再不行回报失败。
     * `rate` 是本次朗读的语速（听力题的 listenRate），-1 表示用朗读设置里的全局语速。
     */
    fun read(context: Context, text: String, language: String = "", notice: ((JSONObject) -> Unit)? = null, rate: Double = -1.0) {
        if (text.isBlank()) return
        val app = context.applicationContext
        main.post {
            val config = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Throwable) { JSONObject() }
            currentRate = if (rate.isFinite() && rate in 0.7..1.5) rate else -1.0
            if (currentRate > 0) config.put("speechRate", currentRate)
            val mode = config.optString("mode", "auto")
            val chosen = when {
                LocalOnlyGuard.enabled -> "system"
                mode == "auto" -> when {
                    deepseekKey(config).isNotEmpty() -> "deepseek"
                    config.optString("azureKey").isNotEmpty() -> "azure"
                    config.optString("aliyunSecret").isNotEmpty() -> "aliyun"
                    TtsProbe.defaultEngine(app).isNotEmpty() -> "system"
                    else -> "edge"
                }
                // 电视没有内置语音引擎时，如果家长已在「模型 API」配过 DeepSeek Key，自动兜底使用 DeepSeek 朗读
                mode == "system" && TtsProbe.defaultEngine(app).isEmpty() && deepseekKey(config).isNotEmpty() -> "deepseek"
                else -> mode
            }
            // 系统 TTS 使用 QUEUE_FLUSH 覆盖旧播报，避免部分固件连续调用 system.stop() + speak() 静默丢弃后续题目
            stopNow(stopSystem = chosen != "system")
            val request = generation
            this.notice = notice
            cachedContext = app
            val clean = safeTruncate(text, 10000)
            val spoken = when (val override = config.optString("readLanguage").trim()) {
                "system" -> ""
                "" -> language
                else -> override
            }
            if (chosen == "system") systemRead(app, clean, spoken, request)
            else SpeechAudioPlayer.cloudPart(app, main, cloudChunks(clean, chosen), 0, spoken, config, chosen, request)
        }
    }

    private fun rateOf(config: JSONObject): Double {
        val raw = if (currentRate in 0.7..1.5) currentRate else config.optDouble("speechRate", 1.0)
        return raw.takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
    }

    /** 题目音频播放：失败时记录诊断 + Toast + 回报给前端 */
    fun playAudio(context: Context, url: String, notice: ((JSONObject) -> Unit)? = null) {
        val app = context.applicationContext
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            recordFailure("audio", "unsupported-url", "", "", false, "音频地址不正确，无法播放", notice)
            return
        }
        if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) {
            recordFailure("audio", "local-only-blocked", "", "", false, "仅本地隐私版仅允许播放本机或局域网内的音频", notice)
            return
        }
        main.post {
            stopNow(); val request = generation
            this.notice = notice
            cachedContext = app
            SpeechAudioPlayer.playUrl(
                context = app,
                url = url,
                request = request,
                onDone = { notify(true, "audio", "done", "", "", false, "") },
                onFailed = { reason, message -> audioFailed(app, request, reason, message) }
            )
        }
    }

    fun stop() { main.post { stopNow() } }

    fun status(): JSONObject = last?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("ok", true).put("mode", "").put("requested", "").put("language", "").put("degraded", false)
        .put("reason", "idle").put("message", "").put("at", 0L)

    fun speaking(): Boolean = speaking

    /** 正在朗读或刚结束朗读不久（Android AudioManager.isMusicActive 在停播后仍会残留 1–2 秒） */
    fun speakingRecently(graceMs: Long = 2500L): Boolean {
        if (speaking) return true
        val at = lastSpokeAt
        if (at <= 0L) return false
        val now = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { return false }
        return now - at < graceMs
    }

    fun failureNotice(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String): JSONObject =
        JSONObject().put("ok", false).put("mode", mode).put("requested", requested).put("language", language)
            .put("degraded", degraded).put("reason", reason).put("message", message).put("at", System.currentTimeMillis())

    /** BCP47 标签标准化：去掉文字扩展避免部分 TTS 引擎返回 LANG_MISSING_DATA */
    fun normalizeLanguageTag(raw: String): String {
        val tag = raw.trim()
        return when {
            tag.isEmpty() -> "zh-CN"
            tag.startsWith("zh-Hans") || tag == "zh" || tag == "zh-CN" -> "zh-CN"
            tag.startsWith("zh-Hant-TW") || tag == "zh-TW" -> "zh-TW"
            tag.startsWith("zh-Hant-HK") || tag == "zh-HK" || tag.startsWith("zh-Hant-MO") -> "zh-HK"
            tag.startsWith("en-US") || tag == "en" -> "en-US"
            tag.startsWith("en-GB") || tag.startsWith("en-") -> "en-GB"
            else -> tag
        }
    }

    fun defaultLanguage(): String = normalizeLanguageTag(Locale.getDefault().toLanguageTag())

    data class LanguagePlan(val requested: String, val language: String, val accepted: Boolean, val degraded: Boolean, val reason: String)

    fun resolveLanguage(requested: String, fallback: String = defaultLanguage(), available: (String) -> Int?): LanguagePlan {
        val want = requested.trim()
        val primary = want.ifEmpty { fallback }
        val status = ask(available, primary)
        if (status == null || status >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, primary, true, false, "available")
        if (primary == fallback) return LanguagePlan(want, primary, false, true, "unavailable")
        val fallbackStatus = ask(available, fallback)
        if (fallbackStatus == null || fallbackStatus >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, fallback, true, true, "fallback")
        return LanguagePlan(want, primary, false, true, "unavailable")
    }

    private fun ask(available: (String) -> Int?, tag: String): Int? = try { available(tag) } catch (_: Exception) { null }

    fun recognitionLanguage(requested: String): String {
        val want = requested.trim()
        if (want.isEmpty()) return defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        if (want in RECOGNITION_LANGUAGES) return want
        val fallback = defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        Diag.warn("Speech", "recognition language $want not supported, degraded to $fallback")
        return fallback
    }

    internal fun currentGeneration(): Int = generation

    internal fun bumpGeneration(): Int {
        pending = null
        notice = null
        return ++generation
    }

    internal fun notify(
        ok: Boolean,
        mode: String,
        reason: String,
        requested: String,
        language: String,
        degraded: Boolean,
        message: String,
        request: Int? = null
    ) {
        if (request != null && request != generation) return
        val payload = failureNotice(mode, reason, requested, language, degraded, message).put("ok", ok)
        last = payload
        if (ok) return
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        if (reason in TOAST_REASONS) main.post {
            if (request != null && request != generation) return@post
            try { cachedContext?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } } catch (_: Exception) { }
        }
        val callback = notice ?: return
        val copy = JSONObject(payload.toString())
        main.post {
            if (request != null && request != generation) return@post
            try { callback(copy) } catch (_: Exception) { }
        }
    }

    private fun recordFailure(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String, callback: ((JSONObject) -> Unit)?) {
        val payload = failureNotice(mode, reason, requested, language, degraded, message)
        last = payload
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        try { callback?.invoke(JSONObject(payload.toString())) } catch (_: Exception) { }
    }

    private fun audioFailed(context: Context, request: Int, reason: String, message: String) {
        if (request != generation) return
        markSpeaking(false)
        notify(false, "audio", reason, "", "", false, message)
        try { Toast.makeText(context, "音频无法播放，请检查网络或改用朗读", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    private fun stopNow(stopSystem: Boolean = true) {
        generation++; pending = null
        if (stopSystem) system?.stop()
        SpeechAudioPlayer.stopNow()
        markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""; retriedDefault = false; retriedSpeakError = false
        notice = null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { markSpeaking(true) }
        override fun onDone(utteranceId: String?) {
            if (utteranceId != lastUtteranceId) return
            markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) {
            Diag.warn("Speech", "utterance error id=$utteranceId code=$errorCode language=${currentLanguage.ifEmpty { "-" }}")
            val request = utteranceId?.split("-")?.getOrNull(1)?.toIntOrNull() ?: return
            if (request != generation) return
            markSpeaking(false)
            val text = currentText; val context = cachedContext
            val fallback = defaultLanguage()
            if (!retriedDefault && context != null && text.isNotEmpty() && currentLanguage != fallback) {
                retriedDefault = true
                Diag.warn("Speech", "retry reading with default language $fallback (was ${currentLanguage.ifEmpty { "-" }})")
                main.post { speakSystem(context, text, fallback, request, "system") }
                return
            }
            notify(false, "system", "utterance-error", currentLanguage, currentLanguage, true,
                "系统语音播放失败（code=$errorCode），可以换一种语言或改用云端语音")
        }
    }

    internal fun fallbackToSystem(context: Context, text: String, language: String, request: Int, mode: String, cloudError: String = "") =
        systemRead(context, text, language, request, mode, cloudError)

    private fun systemRead(context: Context, text: String, language: String, request: Int, mode: String = "system", cloudError: String = "") {
        if (request != generation) return
        cachedContext = context.applicationContext
        val speak = { if (request == generation) speakSystem(context, text, language, request, mode, cloudError) }
        if (ready) speak()
        else {
            pending = speak
            if (system == null) system = TextToSpeech(context) { status ->
                main.post {
                    ready = status == TextToSpeech.SUCCESS
                    val action = pending; pending = null
                    if (ready) system?.setOnUtteranceProgressListener(progress)
                    else { system?.shutdown(); system = null; Diag.warn("Speech", "tts init failed status=$status") }
                    action?.invoke()
                }
            }
        }
    }

    private fun speakSystem(context: Context, text: String, language: String, request: Int, mode: String, cloudError: String = "") {
        val engine = system
        if (engine == null) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError（且本机无内置系统语音）"
            } else {
                "这台电视没有内置的系统语音，读不出来；家长可以在面板「模型 API」填写 DeepSeek Key 后自动使用云端语音"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "engine-unavailable", language, "", cloudError.isNotEmpty(), msg)
            return
        }
        val plan = try {
            resolveLanguage(language) { tag -> try { engine.setLanguage(Locale.forLanguageTag(tag)) } catch (_: Exception) { null } }
        } catch (e: Exception) {
            Diag.warn("Speech", "setLanguage failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            notify(false, mode, "language-check-failed", language, "", false, cloudError.ifEmpty { "系统语音不可用（${e.javaClass.simpleName}）" })
            return
        }
        if (plan.reason == "fallback") Diag.info("Speech", "language ${plan.requested} unavailable, fell back to ${plan.language}")
        if (!plan.accepted) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError（且系统语音不支持 ${plan.requested}）"
            } else {
                "这台设备的系统语音不支持 ${plan.requested}，装个语音包或配置 DeepSeek 语音再试"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "language-unavailable", plan.requested, plan.language, true, msg)
            return
        }
        try { engine.setAudioAttributes(SpeechAudioPlayer.attributesFor(context)) } catch (_: Exception) { }
        try { engine.setSpeechRate(rateOf(currentSettings()).toFloat()) } catch (_: Exception) { }
        currentText = text; currentLanguage = plan.language
        if (plan.degraded) retriedDefault = true
        // 按句子攒到 180 字再排队朗读，避免电视内置 TTS 读长句中途截断
        val max = minOf(180, TextToSpeech.getMaxSpeechInputLength())
        val parts = packedSentences(text, max).ifEmpty { listOf(text) }
        lastUtteranceId = "sotv-$request-${parts.size - 1}"
        parts.forEachIndexed { index, part ->
            val rc = engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "sotv-$request-$index")
            if (rc == TextToSpeech.ERROR) {
                Diag.warn("Speech", "engine.speak returned ERROR on part $index, reinitializing TTS")
                try { engine.shutdown() } catch (_: Exception) { }
                system = null; ready = false
                if (!retriedSpeakError) {
                    retriedSpeakError = true
                    systemRead(context, text, language, request, mode, cloudError)
                } else {
                    notify(false, mode, "speak-error", plan.requested, plan.language, plan.degraded,
                        "系统语音引擎无响应，请重试或改用云端语音")
                }
                return
            }
        }
        markSpeaking(true)
        notify(true, mode, if (plan.degraded) "language-fallback" else "ok", plan.requested.ifEmpty { plan.language }, plan.language, plan.degraded, "")
    }

    private fun currentSettings(): JSONObject = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }

    internal fun safeTruncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    internal fun chunkCodePointSafe(text: String, limit: Int): List<String> {
        if (text.isEmpty() || limit <= 0) return if (text.isEmpty()) emptyList() else listOf(text)
        val out = ArrayList<String>()
        var pos = 0
        while (pos < text.length) {
            var end = minOf(pos + limit, text.length)
            if (end < text.length && end > pos &&
                Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
            ) {
                if (end - 1 > pos) end-- else end++
            }
            out.add(text.substring(pos, end))
            pos = end
        }
        return out
    }

    fun cloudChunks(text: String, mode: String = "azure"): List<String> =
        if (mode == "deepseek") packedSentences(text, 400) else chunkCodePointSafe(text, 150)

    private fun packedSentences(text: String, limit: Int): List<String> {
        val pieces = ArrayList<String>(); val current = StringBuilder()
        fun flush() { val piece = current.toString().trim(); if (piece.isNotEmpty()) pieces.add(piece); current.setLength(0) }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                current.append(ch).append(text[i + 1])
                i += 2
                if (current.length >= limit) flush()
            } else {
                current.append(ch)
                i++
                if (ch in SENTENCE_ENDS || current.length >= limit) flush()
            }
        }
        flush()
        val packed = ArrayList<String>(); val buffer = StringBuilder()
        for (piece in pieces) {
            if (buffer.isNotEmpty() && buffer.length + piece.length > limit) { packed.add(buffer.toString()); buffer.setLength(0) }
            buffer.append(piece)
        }
        if (buffer.isNotEmpty()) packed.add(buffer.toString())
        return packed.ifEmpty { listOf(text) }
    }

    internal fun resolveDeepseekKey(config: JSONObject, gradingJsonProvider: () -> String): String =
        SpeechSettings.resolveDeepseekKey(config, gradingJsonProvider)
    fun deepseekKey(config: JSONObject = currentSettings(), store: LocalStore? = null): String =
        SpeechSettings.deepseekKey(config, store)
    internal fun resolveRecognizeKey(localOnly: Boolean, speechJson: String, gradingJsonProvider: () -> String): String =
        SpeechSettings.resolveRecognizeKey(localOnly, speechJson, gradingJsonProvider)
    fun recognizeKey(store: LocalStore): String = SpeechSettings.recognizeKey(store)
    internal fun applySpeechKeyMutations(next: JSONObject, input: JSONObject) =
        SpeechSettings.applySpeechKeyMutations(next, input)

    internal fun requireCloudHttpAllowed(what: String, localOnly: Boolean = LocalOnlyGuard.enabled) =
        CloudSpeechProviders.requireCloudHttpAllowed(what, localOnly)
    internal fun openCloudConnection(url: String, what: String, localOnly: Boolean = LocalOnlyGuard.enabled): HttpURLConnection =
        CloudSpeechProviders.openCloudConnection(url, what, localOnly)
    internal fun formatDeepSeekError(what: String, code: Int, rawDetail: String?): String =
        CloudSpeechProviders.formatDeepSeekError(what, code, rawDetail)
    fun transcribe(key: String, wav: ByteArray): String = CloudSpeechProviders.transcribe(key, wav)
    internal fun isValidCachedAudio(mode: String, bytes: ByteArray): Boolean =
        CloudSpeechProviders.isValidCachedAudio(mode, bytes)
    internal fun readDiskCacheFromDir(dir: File, mode: String, cacheKey: String): ByteArray? =
        CloudSpeechProviders.readDiskCacheFromDir(dir, mode, cacheKey)
    internal fun writeDiskCacheToDir(dir: File, cacheKey: String, bytes: ByteArray) =
        CloudSpeechProviders.writeDiskCacheToDir(dir, cacheKey, bytes)
    internal fun synthWithCacheCore(cacheDir: File, mode: String, cacheKey: String, synthesize: () -> ByteArray): ByteArray =
        CloudSpeechProviders.synthWithCacheCore(cacheDir, mode, cacheKey, synthesize)

    internal fun validatePcmBufferSize(minBytes: Int): Int = SpeechAudioPlayer.validatePcmBufferSize(minBytes)
    internal fun validateAudioTrackState(state: Int) = SpeechAudioPlayer.validateAudioTrackState(state)
    internal fun validatePcmWrite(written: Int, isCurrent: Boolean): Int = SpeechAudioPlayer.validatePcmWrite(written, isCurrent)
}
