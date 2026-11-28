package com.studyontv.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 微软 Edge Read-Aloud WebSocket 协议编解码：
 * 负责 Sec-MS-GEC 令牌计算、RFC 6455 握手与帧重组，以及 SSML 文本净化。
 */
internal object EdgeTtsProtocol {
    const val HOST = "speech.platform.bing.com"
    const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    const val MAX_AUDIO_BYTES = 4 * 1024 * 1024

    private val HTTP_STATUS_LINE_RE = Regex("^HTTP/\\d+\\.\\d+\\s+(\\d{3})(?:\\s.*)?$")
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    class Handshake403Exception(message: String, val serverMillis: Long?) : IllegalStateException(message)

    /** 生成 v2 握手参数，时间戳按五分钟取整。 */
    fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        var ticks = (nowMillis / 1000L) + 11_644_473_600L
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val raw = "$ticks$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII)
        return EdgeGecV2.encode(raw)
    }

    /** 解析 HTTP 响应头中的 RFC 1123 时间（用于时钟偏差校准）。 */
    fun parseHttpDate(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in formats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                return fmt.parse(text)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    fun parseHttpStatusCode(statusLine: String): Int? =
        HTTP_STATUS_LINE_RE.matchEntire(statusLine.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun expectedAccept(wsKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((wsKey + WS_GUID).toByteArray(Charsets.US_ASCII))
        return MediaStore.encodeBase64(digest)
    }

    fun validateHandshakeResponse(statusLine: String, headers: Map<String, String>, wsKey: String) {
        val code = parseHttpStatusCode(statusLine)
        if (code == 403) {
            throw Handshake403Exception("Edge 语音握手失败：$statusLine", parseHttpDate(headers["date"]))
        }
        require(code == 101) { "Edge 语音握手失败：$statusLine" }
        val accept = headers["sec-websocket-accept"].orEmpty().trim()
        require(accept == expectedAccept(wsKey)) { "Edge 语音握手校验失败（Sec-WebSocket-Accept 不匹配）" }
    }

    fun handshake(input: BufferedInputStream, out: OutputStream, path: String, muid: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = MediaStore.encodeBase64(keyBytes)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(HOST).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: ").append(ORIGIN).append("\r\n")
            append("User-Agent: ").append(USER_AGENT).append("\r\n")
            append("Pragma: no-cache\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Cookie: muid=").append(muid).append(";\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHttpLine(input)
        val headers = HashMap<String, String>()
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        validateHandshakeResponse(statusLine, headers, wsKey)
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException("Edge 语音连接意外关闭")
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val len = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.UTF_8)
            }
            buf.write(b)
            require(buf.size() <= 8192) { "Edge 响应头过长" }
        }
    }

    fun parseCloseInfo(payload: ByteArray): Pair<Int?, String> {
        if (payload.size < 2) return null to ""
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8).trim() else ""
        return code to reason
    }

    /** 读取并重组一条完整的 WebSocket 数据消息（支持连续分片及中途穿插控制帧）。 */
    fun readMessage(input: InputStream, onControl: (EdgeTts.Frame) -> Boolean = { it.opcode != 0x8 }): EdgeTts.Frame? {
        var fragOpcode = -1
        val fragBuffer = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame(input) ?: return null
            if (frame.opcode >= 0x8) {
                if (!onControl(frame)) return null
                continue
            }
            if (frame.opcode != 0x0) {
                require(fragOpcode == -1) { "Edge WebSocket 分片未结束又收到新消息帧" }
                if (frame.fin) return frame
                fragOpcode = frame.opcode
                fragBuffer.reset()
                fragBuffer.write(frame.payload)
            } else {
                require(fragOpcode != -1) { "Edge WebSocket 收到孤立的连续分片帧" }
                require(fragBuffer.size() + frame.payload.size <= MAX_AUDIO_BYTES) { "Edge 帧过大" }
                fragBuffer.write(frame.payload)
                if (frame.fin) {
                    return EdgeTts.Frame(true, fragOpcode, fragBuffer.toByteArray())
                }
            }
        }
    }

    fun readFrame(input: InputStream): EdgeTts.Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var len = (second and 0x7F).toLong()
        if (len == 126L) {
            val b1 = input.read(); val b2 = input.read()
            if (b1 < 0 || b2 < 0) throw EOFException()
            len = (((b1 and 0xFF) shl 8) or (b2 and 0xFF)).toLong()
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) {
                val b = input.read(); if (b < 0) throw EOFException()
                v = (v shl 8) or (b.toLong() and 0xFFL)
            }
            len = v
        }
        require(len in 0..MAX_AUDIO_BYTES) { "Edge 帧过大" }
        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, len.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return EdgeTts.Frame(fin, opcode, payload)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(out, off, size - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return out
    }

    fun sendTextFrame(out: OutputStream, text: String) = sendFrame(out, 0x1, text.toByteArray(Charsets.UTF_8))
    fun sendPongFrame(out: OutputStream, payload: ByteArray) = sendFrame(out, 0xA, payload)
    fun sendCloseFrame(out: OutputStream) {
        try { sendFrame(out, 0x8, ByteArray(0)) } catch (_: Exception) { }
    }

    fun encodeFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write((if (fin) 0x80 else 0x00) or (opcode and 0x0F))
        val maskBit = if (masked) 0x80 else 0x00
        val len = payload.size
        when {
            len <= 125 -> out.write(maskBit or len)
            len <= 65535 -> {
                out.write(maskBit or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) out.write(((len.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        if (masked) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            out.write(mask)
            val maskedPayload = ByteArray(len) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            out.write(maskedPayload)
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun sendFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val encoded = encodeFrame(opcode, payload, masked = true, fin = true)
        synchronized(out) {
            out.write(encoded)
            out.flush()
        }
    }

    fun isoTimestamp(nowMillis: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMillis))
    }

    fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(Locale.US, it) }

    fun buildConfigMessage(ts: String): String =
        "X-Timestamp:$ts\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"

    fun buildSsmlMessage(reqId: String, ts: String, chosenVoice: String, rateStr: String, text: String): String {
        val lang = chosenVoice.split("-").take(2).joinToString("-").ifEmpty { "zh-CN" }
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$chosenVoice'><prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>${escapeXml(text)}</prosody></voice></speak>"
        return "X-RequestId:$reqId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$ts\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    /**
     * 按 XML 1.0 §2.2 净化并转义 SSML 文本：
     * 剥离非法控制字符与孤立代理字符，保留合法 UTF-16 代理对，转义 XML 特殊字符。
     */
    fun escapeXml(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    out.append(ch).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (Character.isLowSurrogate(ch)) {
                i++
                continue
            }
            val code = ch.code
            val xmlValid = ch == '\t' || ch == '\n' || ch == '\r' ||
                (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
            if (xmlValid) {
                when (ch) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '"' -> out.append("&quot;")
                    '\'' -> out.append("&apos;")
                    else -> out.append(ch)
                }
            }
            i++
        }
        return out.toString()
    }
}
