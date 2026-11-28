package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 云端语音合成（DeepSeek / Edge / Azure / 阿里云）、DeepSeek 语音识别及合成音频的内存与磁盘缓存。
 */
internal object CloudSpeechProviders {
    /** 题目重绘或回填草稿会反复触发自动朗读，内存保留最近几段避免重复计费。 */
    private val synthCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 6
    }
    private val tokenLock = Any()
    @Volatile private var token = ""
    @Volatile private var tokenExpires = 0L
    @Volatile private var tokenKey = ""
    private val diskLock = Any()

    fun clearCaches() {
        synchronized(tokenLock) { token = ""; tokenExpires = 0L; tokenKey = "" }
        synchronized(synthCache) { synthCache.clear() }
    }

    fun requireCloudHttpAllowed(what: String, localOnly: Boolean = LocalOnlyGuard.enabled) {
        if (localOnly) throw IllegalStateException("仅本地隐私版（EU Local-Only）已禁用云端$what")
    }

    fun openCloudConnection(url: String, what: String, localOnly: Boolean = LocalOnlyGuard.enabled): HttpURLConnection {
        requireCloudHttpAllowed(what, localOnly)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }
    }

    private fun deepseekBytes(config: JSONObject, text: String): ByteArray {
        val key = SpeechReader.deepseekKey(config)
        require(key.isNotEmpty()) { "还没填 DeepSeek API Key" }
        val voice = config.optString("deepseekVoice").trim().takeIf { it in SpeechReader.DEEPSEEK_VOICES } ?: "stella"
        val body = JSONObject().put("text", text).put("voice_id", voice).put("format", "wav").toString()
        return postBytes("https://api.deepseek.com/v1/tools/tts", key, body, "语音合成")
    }

    private fun postBytes(url: String, key: String, body: String, what: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection(url, what)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = try { connection.errorStream?.use { input -> String(input.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null }
                throw IllegalStateException(formatDeepSeekError(what, code, detail))
            }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    out.write(buffer, 0, size)
                    require(out.size() <= 4_000_000) { "DeepSeek $what 返回的数据过大" }
                }
                out.toByteArray().also { require(it.size > 44) { "DeepSeek $what 返回的数据是空的" } }
            }
        } finally { connection.disconnect() }
    }

    fun formatDeepSeekError(what: String, code: Int, rawDetail: String?): String {
        val parsed = try {
            val j = JSONObject(rawDetail.orEmpty())
            j.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { j.optString("message").orEmpty() }
                .ifBlank { j.optString("error").orEmpty() }
        } catch (_: Exception) { rawDetail.orEmpty().trim() }
        val lower = parsed.lowercase(Locale.US)
        val friendly = when {
            code == 402 || lower.contains("insufficient balance") || lower.contains("insufficient_quota") || lower.contains("quota") ->
                "DeepSeek 账户余额不足，请先到 DeepSeek 开放平台充值"
            code == 401 || code == 403 || lower.contains("invalid_api_key") || lower.contains("authentication") ->
                "DeepSeek API Key 无效或无权限（需使用 DeepSeek 官方 Key）"
            code == 404 ->
                "当前接口不支持 DeepSeek $what（需使用官方 API Key，不支持第三方中转站）"
            code == 413 ->
                "内容过长，超出 DeepSeek $what 单次限制"
            code == 429 || lower.contains("rate limit") || lower.contains("too many requests") ->
                "DeepSeek 请求过于频繁，请稍后再试"
            code in listOf(500, 502, 503, 504) || lower.contains("overloaded") || lower.contains("unavailable") ->
                "DeepSeek 服务暂时繁忙，请稍后再试"
            else -> null
        }
        if (friendly != null) return friendly
        val extra = parsed.take(120).let { if (it.isNotEmpty()) "：$it" else "" }
        return "DeepSeek $what 失败（HTTP $code）$extra"
    }

    /** DeepSeek 语音识别：上传 16 位 PCM WAV（≤8 MiB），阻塞调用需在后台线程执行。 */
    fun transcribe(key: String, wav: ByteArray): String {
        requireCloudHttpAllowed("语音识别")
        require(key.isNotEmpty()) { "还没填 DeepSeek API Key" }
        require(wav.size > 44) { "没有录到声音" }
        require(wav.size <= 8 * 1024 * 1024) { "录音太长，请分段说" }
        val boundary = "----sotv" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"answer.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection("https://api.deepseek.com/v1/tools/asr", "语音识别")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(head.size + wav.size + tail.size)
            connection.outputStream.use { it.write(head); it.write(wav); it.write(tail) }
            val code = connection.responseCode
            val body = try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            } catch (_: Exception) { "" }
            if (code !in 200..299) throw IllegalStateException(formatDeepSeekError("语音识别", code, body))
            return JSONObject(body).optString("text").trim().also { require(it.isNotEmpty()) { "没有听清，请再说一遍" } }
        } finally { connection.disconnect() }
    }

    fun isValidCachedAudio(mode: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return when (mode) {
            "deepseek" -> bytes.size > 44 && WavAudio.isWave(bytes)
            else -> {
                if (bytes.size <= 128) return false
                val head = firstNonWhitespaceByte(bytes) ?: return false
                if (head == '{'.code || head == '<'.code || head == '['.code) return false
                hasMp3Signature(bytes)
            }
        }
    }

    private fun firstNonWhitespaceByte(bytes: ByteArray): Int? {
        val limit = minOf(bytes.size, 64)
        for (i in 0 until limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b != ' '.code && b != '\t'.code && b != '\r'.code && b != '\n'.code) return b
        }
        return null
    }

    private fun hasMp3Signature(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return true
        }
        val scanLimit = minOf(bytes.size - 1, 64)
        for (i in 0 until scanLimit) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                return true
            }
        }
        return false
    }

    private fun diskFileInDir(dir: File, cacheKey: String): File? {
        if (cacheKey.isEmpty()) return null
        if (!dir.exists()) dir.mkdirs()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.bin")
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "tts_cache")

    fun readDiskCacheFromDir(dir: File, mode: String, cacheKey: String): ByteArray? = synchronized(diskLock) {
        val f = diskFileInDir(dir, cacheKey) ?: return null
        if (!f.exists()) return null
        val data = try { f.readBytes() } catch (_: Exception) { null }
        if (data == null || !isValidCachedAudio(mode, data)) {
            try { f.delete() } catch (_: Exception) { }
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        return data
    }

    fun writeDiskCacheToDir(dir: File, cacheKey: String, bytes: ByteArray) {
        synchronized(diskLock) {
            if (cacheKey.isEmpty() || bytes.size > 512 * 1024) return
            val f = diskFileInDir(dir, cacheKey) ?: return
            val tmp = File(f.parentFile, "${f.name}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                        return
                    }
                }
                val parent = f.parentFile ?: return
                val files = parent.listFiles { file -> file.isFile && file.name.endsWith(".bin") } ?: return
                var total = files.sumOf { it.length() }
                if (total > 20L * 1024 * 1024) {
                    for (old in files.sortedBy { it.lastModified() }) {
                        if (total <= 16L * 1024 * 1024) break
                        val len = old.length()
                        if (old.delete()) total -= len
                    }
                }
            } catch (_: Exception) {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun cacheKeyFor(mode: String, config: JSONObject, language: String, part: String): String {
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return when (mode) {
            "deepseek" -> "${SpeechReader.deepseekKey(config).hashCode()}|" + config.optString("deepseekVoice") + "|" + part
            "edge" -> "edge|${config.optString("edgeVoice")}|$language|$rate|$part"
            else -> ""
        }
    }

    fun synthWithCacheCore(
        cacheDir: File,
        mode: String,
        cacheKey: String,
        synthesize: () -> ByteArray
    ): ByteArray {
        if (cacheKey.isNotEmpty()) {
            synchronized(synthCache) { synthCache[cacheKey] }?.let { cached ->
                if (isValidCachedAudio(mode, cached)) return cached
                synchronized(synthCache) { synthCache.remove(cacheKey) }
            }
            readDiskCacheFromDir(cacheDir, mode, cacheKey)?.let {
                synchronized(synthCache) { synthCache[cacheKey] = it }
                return it
            }
        }
        val bytes = synthesize()
        if (!isValidCachedAudio(mode, bytes)) {
            throw IllegalStateException("云端朗读返回的不是音频：${bodySnippet(bytes)}")
        }
        if (cacheKey.isNotEmpty() && bytes.size <= 512 * 1024) {
            synchronized(synthCache) { synthCache[cacheKey] = bytes }
            writeDiskCacheToDir(cacheDir, cacheKey, bytes)
        }
        return bytes
    }

    fun synthWithCache(context: Context, mode: String, config: JSONObject, language: String, part: String): ByteArray {
        val cacheKey = cacheKeyFor(mode, config, language, part)
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return synthWithCacheCore(cacheDir(context), mode, cacheKey) {
            when (mode) {
                "deepseek" -> deepseekBytes(config, part)
                "edge" -> EdgeTts.synthesize(part, config.optString("edgeVoice"), language, rate)
                "azure" -> azure(config, part, language)
                else -> aliyun(config, part)
            }
        }
    }

    /** 云端返回体不是音频时，把开头一小段带进错误消息，家长至少能看到接口说了什么 */
    fun bodySnippet(bytes: ByteArray): String =
        try {
            String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).replace(Regex("\\s+"), " ").trim().take(160)
        } catch (_: Exception) { "" }

    private fun request(url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ByteArray {
        val connection = openCloudConnection(url, "语音合成")
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 10000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body) } }
            require(connection.responseCode in 200..299)
            return connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; bytes.write(buffer, 0, size); require(bytes.size() <= 2_000_000) }
                bytes.toByteArray().also { require(it.isNotEmpty()) }
            }
        } finally { connection.disconnect() }
    }

    private fun xml(text: String) = EdgeTts.escapeXml(text)

    private fun azure(config: JSONObject, text: String, language: String): ByteArray {
        val region = config.getString("azureRegion")
        require(region.matches(Regex("[a-z0-9-]+")))
        val tag = language.ifBlank { SpeechReader.defaultLanguage() }.let { if (it.startsWith("zh") || it.startsWith("en")) it else "zh-CN" }
        val voice = config.optString("azureVoice").ifBlank { when {
            tag.startsWith("en") -> "en-US-JennyNeural"
            tag == "zh-HK" -> "zh-HK-HiuMaanNeural"
            tag == "zh-TW" -> "zh-TW-HsiaoChenNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        } }
        val ssml = "<speak version='1.0' xml:lang='${xml(tag)}'><voice name='${xml(voice)}'>${xml(text)}</voice></speak>"
        return request("https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
            mapOf("Ocp-Apim-Subscription-Key" to config.getString("azureKey"), "Content-Type" to "application/ssml+xml",
                "X-Microsoft-OutputFormat" to "audio-16khz-32kbitrate-mono-mp3"), ssml.toByteArray())
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun aliyun(config: JSONObject, text: String): ByteArray {
        val identity = config.getString("aliyunAccessKeyId") + config.getString("aliyunSecret")
        val currentToken = synchronized(tokenLock) {
            if (token.isEmpty() || identity != tokenKey || System.currentTimeMillis() / 1000 >= tokenExpires - 120) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
                val parameters = sortedMapOf("AccessKeyId" to config.getString("aliyunAccessKeyId"), "Action" to "CreateToken", "Format" to "JSON",
                    "Version" to "2019-02-28", "SignatureMethod" to "HMAC-SHA1", "SignatureVersion" to "1.0", "SignatureNonce" to UUID.randomUUID().toString(), "Timestamp" to timestamp)
                val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                val mac = Mac.getInstance("HmacSHA1")
                mac.init(SecretKeySpec((config.getString("aliyunSecret") + "&").toByteArray(), "HmacSHA1"))
                val signature = MediaStore.encodeBase64(mac.doFinal(("GET&%2F&" + encode(query)).toByteArray()))
                val result = JSONObject(String(request("https://nls-meta.cn-shanghai.aliyuncs.com/?$query&Signature=${encode(signature)}"), Charsets.UTF_8)).getJSONObject("Token")
                token = result.getString("Id"); tokenExpires = result.getLong("ExpireTime"); tokenKey = identity
            }
            token
        }
        val voice = config.optString("aliyunVoice").ifBlank { "xiaoyun" }
        return request("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts?appkey=${encode(config.getString("aliyunAppKey"))}&token=${encode(currentToken)}&text=${encode(text)}&format=mp3&sample_rate=16000&voice=${encode(voice)}")
    }
}
