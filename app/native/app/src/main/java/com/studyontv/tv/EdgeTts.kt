package com.studyontv.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 免费内置微软 Edge 在线朗读（Read-Aloud WebSocket 协议，零第三方依赖）。
 *
 * 使用带连接超时的底层 Socket + TLS 包装 + RFC 6455 WebSocket 握手与分片重组，
 * 当电视机本地时钟失准导致 HTTP 403 时，自动读取响应头里的 `Date` 校准 `Sec-MS-GEC` 重试一次。
 */
object EdgeTts {
    private const val HOST = EdgeTtsProtocol.HOST
    private const val PORT = 443
    private const val TRUSTED_TOKEN = EdgeTtsProtocol.TRUSTED_TOKEN
    private const val CHROMIUM_VERSION = "143.0.3650.75"
    private const val MAX_AUDIO_BYTES = EdgeTtsProtocol.MAX_AUDIO_BYTES

    /** 记录设备时钟与微软服务端时钟的偏差，电视未完成 NTP 校时时后续请求直接复用校准值。 */
    @Volatile private var clockSkewMs = 0L

    val VOICES = listOf(
        "zh-CN-XiaoxiaoNeural",
        "zh-CN-YunxiNeural",
        "zh-CN-YunjianNeural",
        "zh-CN-XiaoyiNeural",
        "zh-HK-HiuGaaiNeural",
        "zh-TW-HsiaoChenNeural",
        "en-US-JennyNeural",
        "en-GB-SoniaNeural",
    )

    internal class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    fun defaultVoice(language: String): String {
        val tag = language.lowercase(Locale.US)
        return when {
            tag.startsWith("zh-hk") || tag.startsWith("yue") -> "zh-HK-HiuGaaiNeural"
            tag.startsWith("zh-tw") -> "zh-TW-HsiaoChenNeural"
            tag.startsWith("en-gb") -> "en-GB-SoniaNeural"
            tag.startsWith("en") -> "en-US-JennyNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        }
    }

    internal fun containsCjk(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF) return true
        }
        return false
    }

    internal fun hasSpeakableContent(text: String): Boolean {
        for (ch in text) {
            if (Character.isLetterOrDigit(ch)) return true
        }
        return false
    }

    /**
     * Edge 的纯英文音色遇到中文文本会静默丢弃所有汉字并返回 0 字节空音频，
     * 含汉字时自动切到可中英混读的中文音色。
     */
    internal fun resolveEffectiveVoice(text: String, configuredVoice: String, language: String): String {
        val base = configuredVoice.trim().ifEmpty { defaultVoice(language) }
        if (base.startsWith("en-", ignoreCase = true) && containsCjk(text)) {
            return "zh-CN-XiaoxiaoNeural"
        }
        return base
    }

    internal fun requireOnlineAllowed(localOnly: Boolean = LocalOnlyGuard.enabled) {
        if (localOnly) throw IllegalStateException("仅本地隐私版（EU Local-Only）已禁用 Edge 在线语音")
    }

    internal fun shouldRetryAfter403(usedNowMs: Long, serverMs: Long?): Boolean {
        if (serverMs == null) return false
        return secMsGec(serverMs) != secMsGec(usedNowMs) || kotlin.math.abs(serverMs - usedNowMs) >= 60_000L
    }

    internal fun orderAddressesIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        val v4 = addresses.filterIsInstance<Inet4Address>()
        val other = addresses.filter { it !is Inet4Address }
        return v4 + other
    }

    fun synthesize(text: String, voice: String, language: String = "", rate: Double = 1.0): ByteArray {
        requireOnlineAllowed()
        require(hasSpeakableContent(text)) { "朗读内容不含可发声文字" }
        val chosenVoice = resolveEffectiveVoice(text, voice, language)
        val pct = (((rate.coerceIn(0.7, 1.5)) - 1.0) * 100.0).toInt()
        val rateStr = if (pct >= 0) "+${pct}%" else "${pct}%"
        return synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = 0, allowTransientRetry = true)
    }

    private fun synthesizeWithRetry(
        text: String,
        chosenVoice: String,
        rateStr: String,
        addressOffset: Int,
        allowTransientRetry: Boolean
    ): ByteArray {
        val usedNowMs = System.currentTimeMillis() + clockSkewMs
        return try {
            synthesizeOnce(text, chosenVoice, rateStr, usedNowMs, addressOffset)
        } catch (e: EdgeTtsProtocol.Handshake403Exception) {
            val serverMs = e.serverMillis
            if (serverMs == null || !shouldRetryAfter403(usedNowMs, serverMs)) {
                throw IllegalStateException(e.message ?: "Edge 语音握手被拒绝（HTTP 403）")
            }
            clockSkewMs = serverMs - System.currentTimeMillis()
            try {
                synthesizeOnce(text, chosenVoice, rateStr, serverMs, addressOffset)
            } catch (retryErr: EdgeTtsProtocol.Handshake403Exception) {
                throw IllegalStateException(retryErr.message ?: "Edge 语音握手被拒绝（HTTP 403）")
            }
        } catch (e: SocketException) {
            if (!allowTransientRetry) throw e
            synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = addressOffset + 1, allowTransientRetry = false)
        } catch (e: EOFException) {
            if (!allowTransientRetry) throw e
            synthesizeWithRetry(text, chosenVoice, rateStr, addressOffset = addressOffset + 1, allowTransientRetry = false)
        }
    }

    private fun openTlsSocket(addressOffset: Int = 0): SSLSocket {
        val resolved = orderAddressesIpv4First(InetAddress.getAllByName(HOST).toList())
        val candidates = if (resolved.isEmpty()) {
            listOf<InetAddress?>(null)
        } else {
            val n = resolved.size
            val shift = ((addressOffset % n) + n) % n
            (0 until minOf(n, 4)).map { i -> resolved[(shift + i) % n] }
        }
        var lastError: Throwable? = null
        for (addr in candidates) {
            val raw = Socket()
            try {
                val target = if (addr != null) InetSocketAddress(addr, PORT) else InetSocketAddress(HOST, PORT)
                raw.connect(target, 4_500)
                raw.soTimeout = 15_000
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                return (sslFactory.createSocket(raw, HOST, PORT, true) as SSLSocket).apply {
                    soTimeout = 15_000
                    configureTlsSocket(this, HOST)
                    startHandshake()
                    if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, session)) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("Hostname $HOST not verified")
                    }
                }
            } catch (e: Throwable) {
                try { raw.close() } catch (_: Exception) { }
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法连接 Edge 语音服务")
    }

    /**
     * Android 5.x/6.x（API 21–23）上对已连接 IP 的 Socket 包装 SSLSocket 时默认不发 TLS SNI，
     * 显式开启 SNI 与 TLSv1.2 以兼容老电视并保留主机名校验。
     */
    internal fun configureTlsSocket(socket: SSLSocket, host: String) {
        val supported = socket.supportedProtocols.orEmpty()
        val preferred = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (preferred.isNotEmpty()) {
            try { socket.enabledProtocols = preferred.toTypedArray() } catch (_: Throwable) { }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(host))
                endpointIdentificationAlgorithm = "HTTPS"
            }
        } else {
            try {
                socket.javaClass.getMethod("setHostname", String::class.java).invoke(socket, host)
            } catch (_: Throwable) { }
            try {
                socket.javaClass.getMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType).invoke(socket, true)
            } catch (_: Throwable) { }
        }
    }

    private fun synthesizeOnce(
        text: String,
        chosenVoice: String,
        rateStr: String,
        nowMillis: Long,
        addressOffset: Int = 0
    ): ByteArray {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val muid = EdgeTtsProtocol.randomHex(16)
        val path = "/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${secMsGec(nowMillis)}" +
            "&Sec-MS-GEC-Version=2-$CHROMIUM_VERSION" +
            "&ConnectionId=$connId"

        val socket = openTlsSocket(addressOffset)
        try {
            val out = socket.outputStream
            val input = BufferedInputStream(socket.inputStream, 16 * 1024)
            EdgeTtsProtocol.handshake(input, out, path, muid)

            val ts = EdgeTtsProtocol.isoTimestamp(nowMillis)
            EdgeTtsProtocol.sendTextFrame(out, EdgeTtsProtocol.buildConfigMessage(ts))

            val reqId = UUID.randomUUID().toString().replace("-", "")
            EdgeTtsProtocol.sendTextFrame(out, EdgeTtsProtocol.buildSsmlMessage(reqId, ts, chosenVoice, rateStr, text))

            val audio = ByteArrayOutputStream()
            var closeDetail = ""
            while (true) {
                val message = readMessage(input) { control ->
                    when (control.opcode) {
                        0x8 -> {
                            val (code, reason) = parseCloseInfo(control.payload)
                            closeDetail = listOfNotNull(code?.let { "code=$it" }, reason.takeIf { it.isNotEmpty() }).joinToString(" ")
                            false
                        }
                        0x9 -> {
                            EdgeTtsProtocol.sendPongFrame(out, control.payload)
                            true
                        }
                        else -> true
                    }
                } ?: break
                when (message.opcode) {
                    0x1 -> {
                        val msg = String(message.payload, Charsets.UTF_8)
                        if (msg.contains("Path:turn.end")) break
                    }
                    0x2 -> {
                        val data = message.payload
                        if (data.size >= 2) {
                            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                            val bodyStart = 2 + headerLen
                            if (bodyStart < data.size) {
                                require(audio.size() + (data.size - bodyStart) <= MAX_AUDIO_BYTES) { "Edge 语音返回数据过大" }
                                audio.write(data, bodyStart, data.size - bodyStart)
                            }
                        }
                    }
                }
            }
            EdgeTtsProtocol.sendCloseFrame(out)
            val result = audio.toByteArray()
            require(result.isNotEmpty()) {
                if (closeDetail.isNotEmpty()) "Edge 语音返回空音频（$closeDetail）" else "Edge 语音返回空音频"
            }
            return result
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    internal fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String = EdgeTtsProtocol.secMsGec(nowMillis)
    internal fun parseHttpDate(value: String?): Long? = EdgeTtsProtocol.parseHttpDate(value)
    internal fun parseHttpStatusCode(statusLine: String): Int? = EdgeTtsProtocol.parseHttpStatusCode(statusLine)
    internal fun expectedAccept(wsKey: String): String = EdgeTtsProtocol.expectedAccept(wsKey)
    internal fun validateHandshakeResponse(statusLine: String, headers: Map<String, String>, wsKey: String) =
        EdgeTtsProtocol.validateHandshakeResponse(statusLine, headers, wsKey)
    internal fun parseCloseInfo(payload: ByteArray): Pair<Int?, String> = EdgeTtsProtocol.parseCloseInfo(payload)
    internal fun readMessage(input: InputStream, onControl: (Frame) -> Boolean = { it.opcode != 0x8 }): Frame? =
        EdgeTtsProtocol.readMessage(input, onControl)
    internal fun readFrame(input: InputStream): Frame? = EdgeTtsProtocol.readFrame(input)
    internal fun encodeFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true): ByteArray =
        EdgeTtsProtocol.encodeFrame(opcode, payload, masked, fin)
    internal fun escapeXml(s: String): String = EdgeTtsProtocol.escapeXml(s)
}
