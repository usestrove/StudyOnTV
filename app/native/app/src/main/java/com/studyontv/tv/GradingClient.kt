package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容模型接口的 HTTP 客户端、地址安全校验、错误本地化与 JSON 响应提取。
 */
internal object GradingClient {
    class ApiError(message: String) : Exception(message)

    private val thinkBlockRe = Regex("(?is)<(think|thinking)\\b[^>]*>.*?</\\1>")
    private val fenceBlockRe = Regex("(?s)```(?:json|JSON)?\\s*(.*?)\\s*```")

    /** 允许 http 只限局域网 / 本机（本地模型），公网必须 https，避免 API Key 明文经过互联网 */
    fun checkEndpoint(raw: String, localOnly: Boolean = LocalOnlyGuard.enabled): URL {
        val trimmed = raw.trim()
        val url = try { URL(trimmed) } catch (_: Exception) { throw IllegalArgumentException("请填写完整的接口地址，例如 https://api.deepseek.com/v1") }
        require(url.userInfo == null && url.ref == null && url.host.isNotEmpty()) { "接口地址格式不正确" }
        if (localOnly && !LocalOnlyGuard.isLocalUrl(trimmed)) {
            throw IllegalArgumentException("仅本地隐私版（EU Local-Only）仅允许连接局域网或本机的 AI 服务（如局域网 Ollama）")
        }
        require(url.protocol == "https" || (url.protocol == "http" && privateHost(url.host))) { "公网接口需要使用 HTTPS；http 只允许局域网地址（如本地 Ollama）" }
        return url
    }

    fun isPrivateAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress

    fun isPrivateAddresses(addresses: Array<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all(::isPrivateAddress)

    fun privateHost(host: String): Boolean = try {
        isPrivateAddresses(InetAddress.getAllByName(host))
    } catch (_: Exception) { false }

    private fun base(raw: String): String =
        raw.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')

    fun chatUrl(raw: String): String { checkEndpoint(raw); return base(raw) + "/chat/completions" }
    fun modelsUrl(raw: String): String { checkEndpoint(raw); return base(raw) + "/models" }
    fun hostOf(raw: String): String = try { URL(raw).host ?: "" } catch (_: Exception) { "invalid-url" }

    fun validateTemperature(value: Double): Double {
        require(value.isFinite() && value in 0.0..2.0) { "温度需要在 0–2 之间" }
        return value
    }

    /** 仅当上游明确报错不支持对应可选字段时才移除或改名重试，避免对无关 400 做无效重试。 */
    fun applyRequestFallback(body: JSONObject, error: String?): String? {
        val message = error.orEmpty()
        if (!message.contains("HTTP 400") && !message.contains("HTTP 422")) return null
        val lower = message.lowercase()
        return when {
            body.has("response_format") && ("response_format" in lower || "json_object" in lower) -> {
                body.remove("response_format")
                "without response_format"
            }
            body.has("top_p") && "top_p" in lower -> {
                body.remove("top_p")
                "without top_p"
            }
            body.has("max_tokens") && ("max_tokens" in lower || "max_completion_tokens" in lower) -> {
                body.put("max_completion_tokens", body.remove("max_tokens"))
                "max_tokens → max_completion_tokens"
            }
            else -> null
        }
    }

    fun request(url: String, key: String, body: JSONObject?, timeout: Int = 30000): JSONObject {
        val started = System.currentTimeMillis()
        val host = hostOf(url)
        val method = if (body == null) "GET" else "POST"
        fun elapsed() = System.currentTimeMillis() - started
        LocalOnlyGuard.requireLocalUrl(url, "AI 服务（如局域网 Ollama）")
        val connection = URL(url).openConnection() as HttpURLConnection
        var responded = false
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000; connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "StudyOnTV")
            if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            val code = connection.responseCode
            responded = true
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    if (out.size() + n > 2_000_000) {
                        Diag.warn("AI", "$method $host response too large bytes>${out.size()} elapsed=${elapsed()}ms")
                        throw ApiError("响应过大")
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) {
                val detail = extractErrorDetail(text)
                Diag.warn("AI", "$method $host http=$code elapsed=${elapsed()}ms timeout=${timeout}ms detail=${detail.take(120)}")
                throw ApiError(localizeApiError(code, detail))
            }
            Diag.info("AI", "$method $host http=$code elapsed=${elapsed()}ms bytes=${bytes.size}")
            return try { JSONObject(text) } catch (_: Exception) {
                Diag.warn("AI", "$method $host not-json elapsed=${elapsed()}ms")
                throw ApiError("返回内容不是 JSON，请确认地址是 OpenAI 兼容接口")
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: UnknownHostException) {
            Diag.warn("AI", "$method $host failed=${e.javaClass.simpleName} elapsed=${elapsed()}ms")
            throw ApiError("无法解析域名，请检查网络或地址")
        } catch (e: SocketTimeoutException) {
            val connectStage = !responded && e.message.orEmpty().contains("connect", ignoreCase = true)
            Diag.warn("AI", "$method $host failed=${e.javaClass.simpleName} stage=${if (connectStage) "connect" else "read"} elapsed=${elapsed()}ms timeout=${timeout}ms")
            throw ApiError(if (connectStage)
                "连接 $host 超时（20 秒），电视可能访问不到这个地址。请确认电视能上网，或用手机热点对比一次"
            else
                "已连上 $host，但 ${timeout / 1000} 秒内没有返回内容，模型可能过慢或服务限流")
        } catch (e: ConnectException) {
            Diag.warn("AI", "$method $host failed=${e.javaClass.simpleName} elapsed=${elapsed()}ms detail=${e.message.orEmpty().take(120)}")
            throw ApiError("无法连接 $host（${e.message?.take(60)}）")
        } catch (e: SSLException) {
            Diag.warn("AI", "$method $host failed=${e.javaClass.simpleName} elapsed=${elapsed()}ms detail=${e.message.orEmpty().take(120)}")
            throw ApiError("HTTPS 握手失败，设备系统可能太旧：${e.message?.take(60)}")
        } catch (e: IOException) {
            Diag.warn("AI", "$method $host failed=${e.javaClass.simpleName} elapsed=${elapsed()}ms detail=${e.message.orEmpty().take(160)}")
            throw e
        } finally { connection.disconnect() }
    }

    fun localizeApiError(code: Int, rawDetail: String?): String {
        val detail = rawDetail.orEmpty().trim()
        val lower = detail.lowercase(Locale.US)
        return when {
            code == 402 || lower.contains("insufficient balance") || lower.contains("insufficient_quota") || lower.contains("exceeded your current quota") ->
                "账户余额不足，请先到服务商平台充值后再试"
            code == 401 || code == 403 || lower.contains("invalid_api_key") || lower.contains("incorrect api key") || lower.contains("authentication fails") ->
                "API Key 无效或没有权限，请检查密钥是否填写正确"
            code == 404 || lower.contains("model_not_found") || lower.contains("does not exist") ->
                "接口地址或模型名称不存在（HTTP $code），请检查接口地址（如 /v1）与模型名称"
            code == 429 || lower.contains("rate limit") || lower.contains("too many requests") ->
                "请求过于频繁或触发限流，请稍后再试"
            lower.contains("context length") || lower.contains("maximum context") || (lower.contains("token") && lower.contains("limit")) ->
                "输入内容过长，超出模型单次处理上限，请精简后再试"
            code in listOf(500, 502, 503, 504) || lower.contains("overloaded") || lower.contains("service unavailable") ->
                "AI 服务暂时繁忙（HTTP $code），请稍后再试"
            else -> "服务返回错误（HTTP $code）" + if (detail.isNotEmpty()) "：${detail.take(120)}" else ""
        }
    }

    fun chat(config: JSONObject, messages: JSONArray, maxTokens: Int, timeout: Int = 30000): String {
        val started = System.currentTimeMillis()
        val model = config.optString("model")
        val body = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", validateTemperature(config.optDouble("temperature", 0.0)))
            .put("max_tokens", maxTokens).put("response_format", JSONObject().put("type", "json_object"))
        val url = chatUrl(config.getString("endpoint"))
        val key = config.optString("apiKey")
        var attempt = 0
        var envelope: JSONObject? = null
        while (envelope == null) {
            try {
                envelope = request(url, key, body, timeout)
            } catch (e: ApiError) {
                val fallback = applyRequestFallback(body, e.message) ?: throw e
                attempt++
                Diag.info("AI", "chat ${hostOf(url)} retry#$attempt $fallback model=$model")
            }
        }
        val response = envelope ?: throw ApiError("模型没有返回结果")
        val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
        if (content == null) {
            Diag.warn("AI", "chat ${hostOf(url)} bad-envelope model=$model elapsed=${System.currentTimeMillis() - started}ms")
            throw ApiError("返回格式不是 chat/completions")
        }
        Diag.info("AI", "chat ok model=$model elapsed=${System.currentTimeMillis() - started}ms chars=${content.length} retried=${attempt > 0}")
        return content
    }

    private fun JSONObject.nonBlankScalar(key: String): String {
        if (isNull(key)) return ""
        val value = opt(key) ?: return ""
        if (value is JSONObject || value is JSONArray) return ""
        return value.toString().trim()
    }

    fun extractErrorDetail(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val json = JSONObject(trimmed)
            val errorObj = json.optJSONObject("error")
            errorObj?.nonBlankScalar("message").orEmpty()
                .ifBlank { json.nonBlankScalar("message") }
                .ifBlank { json.nonBlankScalar("error") }
                .ifBlank {
                    errorObj?.let {
                        listOf(it.nonBlankScalar("type"), it.nonBlankScalar("code"))
                            .filter(String::isNotEmpty)
                            .distinct()
                            .joinToString(": ")
                    }.orEmpty()
                }
                .ifBlank { errorObj?.toString()?.takeIf { it != "{}" }?.take(160).orEmpty() }
                .ifBlank { trimmed.take(160) }
        } catch (_: Exception) {
            trimmed.take(160)
        }
    }

    fun extractJson(text: String): JSONObject {
        val stripped = thinkBlockRe.replace(text, "").trim()
        for (match in fenceBlockRe.findAll(stripped)) {
            findFirstJsonObject(match.groupValues[1])?.let { return it }
        }
        findFirstJsonObject(stripped)?.let { return it }
        throw ApiError("模型没有按要求返回 JSON：" + stripped.ifEmpty { text }.take(80))
    }

    private fun findFirstJsonObject(input: String): JSONObject? {
        var pos = 0
        while (pos < input.length) {
            val start = input.indexOf('{', pos)
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (i in start until input.length) {
                val c = input[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break
                            }
                        }
                    }
                }
            }
            if (end > start) {
                try {
                    return JSONObject(input.substring(start, end + 1))
                } catch (_: Exception) {
                    // 跳过非 JSON 花括号片段（如 LaTeX 公式或说明文字）继续扫描
                }
            }
            pos = start + 1
        }
        return null
    }

    /** 剔除题目 JSON 里任何内联的 Base64 图片字段，避免数十万字符把文本提示词撑爆。 */
    fun stripInlineQuestionMedia(question: JSONObject): JSONObject {
        val copy = JSONObject(question.toString())
        if (copy.optString("image").startsWith("data:image/", ignoreCase = true)) {
            copy.remove("image")
        }
        copy.optJSONArray("images")?.let { arr ->
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (!v.startsWith("data:image/", ignoreCase = true)) kept.put(v)
            }
            if (kept.length() == 0) copy.remove("images") else copy.put("images", kept)
        }
        copy.optJSONArray("optionImages")?.let { arr ->
            val kept = JSONArray()
            var anyKept = false
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                val clean = if (v.startsWith("data:image/", ignoreCase = true)) "" else v
                if (clean.isNotEmpty()) anyKept = true
                kept.put(clean)
            }
            if (!anyKept) copy.remove("optionImages") else copy.put("optionImages", kept)
        }
        return copy
    }
}
