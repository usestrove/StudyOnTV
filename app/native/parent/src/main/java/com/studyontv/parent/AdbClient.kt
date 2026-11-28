package com.studyontv.parent

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 纯 Kotlin 零依赖无线 ADB（TCP v1）客户端。
 * 支持：
 * - `CNXN` + RSA `AUTH`（自动尝试已授信签名，未授信时发送公钥并回调提示用户在电视上点「一律允许」）；
 * - `shell:<cmd>` 命令执行；
 * - `sync:` 大文件推送（自动适配老电视 4KB payload 与新电视 256KB payload）。
 */
class AdbClient internal constructor(
    private val transport: Transport,
    val banner: String,
    val peerVersion: Int,
    val peerMaxPayload: Int
) : Closeable {

    interface Transport : Closeable {
        val input: InputStream
        val output: OutputStream
        fun setSoTimeout(timeoutMs: Int)
    }

    private class SocketTransport(private val socket: Socket) : Transport {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()
        override fun setSoTimeout(timeoutMs: Int) {
            socket.soTimeout = timeoutMs
        }
        override fun close() {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    internal data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private var nextLocalId = 1

    /** 在远端执行单条 shell 命令并返回标准输出（合并 stderr）。 */
    fun shell(command: String, timeoutMs: Int = 30_000): String {
        transport.setSoTimeout(timeoutMs)
        val localId = nextLocalId++
        val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        writePacket(Packet(A_OPEN, localId, 0, dest))

        val remoteId = awaitStreamOpen(localId)
        val out = ByteArrayOutputStream()
        while (true) {
            val pkt = readPacket(peerVersion)
            when (pkt.command) {
                A_WRTE -> {
                    if (pkt.arg1 == localId) {
                        if (out.size() < MAX_SHELL_OUTPUT_BYTES) {
                            val remain = MAX_SHELL_OUTPUT_BYTES - out.size()
                            out.write(pkt.payload, 0, minOf(pkt.payload.size, remain))
                        }
                        writePacket(Packet(A_OKAY, localId, pkt.arg0, EMPTY))
                    } else {
                        writePacket(Packet(A_CLSE, pkt.arg1, pkt.arg0, EMPTY))
                    }
                }
                A_CLSE -> {
                    if (pkt.arg1 == localId) {
                        writePacket(Packet(A_CLSE, localId, remoteId, EMPTY))
                        break
                    }
                }
                A_OKAY -> {
                    // 忽略多余 ACK
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 通过 ADB `sync:` 协议推送本地文件到电视（默认权限 0644）。 */
    fun pushFile(
        localFile: File,
        remotePath: String,
        onProgress: (percent: Int) -> Unit = {}
    ) {
        require(localFile.isFile) { "本地安装包不存在：${localFile.path}" }
        val total = localFile.length()
        require(total > 0L) { "本地安装包为空文件" }
        FileInputStream(localFile).use { fis ->
            pushStream(
                source = fis,
                totalBytes = total,
                remotePath = remotePath,
                mtimeSeconds = (localFile.lastModified() / 1000L).coerceAtLeast(1L),
                onProgress = onProgress
            )
        }
    }

    internal fun pushStream(
        source: InputStream,
        totalBytes: Long,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE,
        mtimeSeconds: Long = System.currentTimeMillis() / 1000L,
        onProgress: (percent: Int) -> Unit = {}
    ) {
        require(remotePath.startsWith("/")) { "远端路径必须以 / 开头" }
        transport.setSoTimeout(60_000)
        val localId = nextLocalId++
        writePacket(Packet(A_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8)))
        val remoteId = awaitStreamOpen(localId)

        try {
            // 1. SEND + "$remotePath,${0x8000 | mode}"
            val spec = "$remotePath,${0x8000 or (mode and 0x0FFF)}".toByteArray(Charsets.UTF_8)
            val sendFrame = ByteBuffer.allocate(8 + spec.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(SYNC_SEND)
                .putInt(spec.size)
                .put(spec)
                .array()
            writeStreamAndAwaitOkay(localId, remoteId, sendFrame)

            // 2. DATA 块流式推送：严格保持 8 + chunkSize <= peerMaxPayload，兼顾老旧电视 4KB payload 与现代电视高吞吐
            val maxChunk = minOf(peerMaxPayload - 8, MAX_SYNC_DATA_CHUNK).coerceAtLeast(1024)
            val buf = ByteArray(maxChunk)
            var sent = 0L
            var lastPct = -1
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                val dataFrame = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
                    .put(SYNC_DATA)
                    .putInt(n)
                    .put(buf, 0, n)
                    .array()
                writeStreamAndAwaitOkay(localId, remoteId, dataFrame)
                sent += n
                val pct = if (totalBytes > 0L) ((sent * 100L) / totalBytes).toInt().coerceIn(0, 100) else 100
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }

            // 3. DONE + mtime
            val doneFrame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(SYNC_DONE)
                .putInt(mtimeSeconds.toInt())
                .array()
            writeStreamAndAwaitOkay(localId, remoteId, doneFrame)

            // 4. 等待 adbd 返回 OKAY 或 FAIL + msg
            val reply = ByteArrayOutputStream()
            while (true) {
                val pkt = readPacket(peerVersion)
                when (pkt.command) {
                    A_WRTE -> {
                        if (pkt.arg1 == localId) {
                            reply.write(pkt.payload)
                            writePacket(Packet(A_OKAY, localId, pkt.arg0, EMPTY))
                            val bytes = reply.toByteArray()
                            if (bytes.size >= 8) {
                                val tag = String(bytes, 0, 4, Charsets.US_ASCII)
                                val arg = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                when (tag) {
                                    "OKAY" -> break
                                    "FAIL" -> {
                                        val msgLen = arg.coerceIn(0, 4096)
                                        if (bytes.size >= 8 + msgLen) {
                                            val reason = String(bytes, 8, msgLen, Charsets.UTF_8)
                                            throw IOException("电视接收安装包失败：$reason")
                                        }
                                    }
                                    else -> throw IOException("电视返回未知同步状态：$tag")
                                }
                            }
                        }
                    }
                    A_CLSE -> {
                        if (pkt.arg1 == localId) {
                            throw IOException("推送完成前电视提前关闭了 ADB 连接")
                        }
                    }
                }
            }

            // 5. QUIT
            val quitFrame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(SYNC_QUIT)
                .putInt(0)
                .array()
            try {
                writePacket(Packet(A_WRTE, localId, remoteId, quitFrame))
            } catch (_: Exception) {
            }
            onProgress(100)
        } finally {
            try {
                writePacket(Packet(A_CLSE, localId, remoteId, EMPTY))
            } catch (_: Exception) {
            }
        }
    }

    private fun awaitStreamOpen(localId: Int): Int {
        while (true) {
            val pkt = readPacket(peerVersion)
            when (pkt.command) {
                A_OKAY -> if (pkt.arg1 == localId) return pkt.arg0
                A_CLSE -> if (pkt.arg1 == localId) {
                    throw IOException("电视拒绝打开 ADB 通道（请确认已允许调试）")
                }
                A_WRTE -> {
                    // 防御性清理上一条通道残留数据包
                    writePacket(Packet(A_CLSE, pkt.arg1, pkt.arg0, EMPTY))
                }
            }
        }
    }

    private fun writeStreamAndAwaitOkay(localId: Int, remoteId: Int, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkLen = minOf(peerMaxPayload, data.size - offset)
            val slice = if (offset == 0 && chunkLen == data.size) data else data.copyOfRange(offset, offset + chunkLen)
            writePacket(Packet(A_WRTE, localId, remoteId, slice))
            offset += chunkLen
            while (true) {
                val pkt = readPacket(peerVersion)
                when (pkt.command) {
                    A_OKAY -> if (pkt.arg1 == localId) break
                    A_CLSE -> if (pkt.arg1 == localId) {
                        throw IOException("文件传输过程中电视断开了 ADB 连接")
                    }
                    A_WRTE -> {
                        // 极少数情况同步通道在传输中提前发 FAIL
                        writePacket(Packet(A_OKAY, localId, pkt.arg0, EMPTY))
                        if (pkt.arg1 == localId && pkt.payload.size >= 8 &&
                            String(pkt.payload, 0, 4, Charsets.US_ASCII) == "FAIL"
                        ) {
                            val msg = String(pkt.payload, 8, pkt.payload.size - 8, Charsets.UTF_8)
                            throw IOException("电视中止接收文件：$msg")
                        }
                    }
                }
            }
        }
    }

    private fun writePacket(packet: Packet) {
        writePacket(transport.output, packet)
    }

    private fun readPacket(negotiatedVersion: Int): Packet =
        readPacket(transport.input, negotiatedVersion)

    override fun close() {
        transport.close()
    }

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val ADB_VERSION = 0x01000000
        const val ADB_VERSION_SKIP_CHECKSUM = 0x01000001
        const val CLIENT_MAX_PAYLOAD = 256 * 1024
        const val MAX_FRAME_PAYLOAD = 1024 * 1024
        const val MAX_SYNC_DATA_CHUNK = 32 * 1024
        const val MAX_SHELL_OUTPUT_BYTES = 2 * 1024 * 1024
        const val DEFAULT_FILE_MODE = 420 // 0644

        private val EMPTY = ByteArray(0)
        private val SYNC_SEND = "SEND".toByteArray(Charsets.US_ASCII)
        private val SYNC_DATA = "DATA".toByteArray(Charsets.US_ASCII)
        private val SYNC_DONE = "DONE".toByteArray(Charsets.US_ASCII)
        private val SYNC_QUIT = "QUIT".toByteArray(Charsets.US_ASCII)

        /**
         * 连接局域网内的无线 ADB 设备（仅允许 RFC1918 私有局域网 IP）。
         */
        fun connect(
            host: String,
            port: Int = LanNet.DEFAULT_ADB_PORT,
            crypto: AdbCrypto,
            connectTimeoutMs: Int = 5_000,
            authWaitTimeoutMs: Int = 60_000,
            onWaitingForApproval: () -> Unit = {}
        ): AdbClient {
            require(LanNet.isPrivateLanIp(host)) { "仅允许连接家庭局域网内设备（拒绝：$host）" }
            require(port in 1024..65535) { "无效 ADB 端口：$port" }
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                return handshake(
                    transport = SocketTransport(socket),
                    crypto = crypto,
                    initialTimeoutMs = 8_000,
                    authWaitTimeoutMs = authWaitTimeoutMs,
                    onWaitingForApproval = onWaitingForApproval
                )
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) { }
                throw e
            }
        }

        internal fun handshake(
            transport: Transport,
            crypto: AdbCrypto,
            initialTimeoutMs: Int = 8_000,
            authWaitTimeoutMs: Int = 60_000,
            onWaitingForApproval: () -> Unit = {}
        ): AdbClient {
            transport.setSoTimeout(initialTimeoutMs)
            writePacket(
                transport.output,
                Packet(A_CNXN, ADB_VERSION, CLIENT_MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
            )

            var triedSignature = false
            var sentPublicKey = false

            while (true) {
                val pkt = try {
                    readPacket(transport.input, ADB_VERSION_SKIP_CHECKSUM)
                } catch (e: SocketTimeoutException) {
                    if (sentPublicKey) {
                        throw IOException("等待电视确认授权超时（60 秒），请在电视弹窗上按遥控器选择「一律允许」后重试", e)
                    }
                    throw IOException("连接电视 ADB 超时，请确认电视和手机在同一 Wi-Fi 且已开启无线调试", e)
                } catch (e: EOFException) {
                    if (sentPublicKey) {
                        throw IOException("电视关闭了授权连接（可能取消了授权弹窗或已被其他设备占用 ADB）", e)
                    }
                    throw IOException("电视主动断开了 ADB 连接（可能端口未启用或已被电脑占用）", e)
                }

                when (pkt.command) {
                    A_CNXN -> {
                        val banner = String(pkt.payload, Charsets.UTF_8).trimEnd('\u0000')
                        val peerVersion = pkt.arg0
                        val peerMax = pkt.arg1.coerceIn(4096, CLIENT_MAX_PAYLOAD)
                        return AdbClient(transport, banner, peerVersion, peerMax)
                    }
                    A_AUTH -> {
                        if (pkt.arg0 != AUTH_TOKEN) {
                            throw IOException("电视返回异常 ADB 认证类型：${pkt.arg0}")
                        }
                        if (!triedSignature) {
                            triedSignature = true
                            val sig = crypto.signToken(pkt.payload)
                            writePacket(transport.output, Packet(A_AUTH, AUTH_SIGNATURE, 0, sig))
                        } else if (!sentPublicKey) {
                            sentPublicKey = true
                            onWaitingForApproval()
                            transport.setSoTimeout(authWaitTimeoutMs)
                            writePacket(transport.output, Packet(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.androidPublicKey))
                        } else {
                            throw IOException("电视拒绝了 ADB 公钥授权")
                        }
                    }
                    else -> {
                        throw IOException("ADB 握手收到非预期指令：0x${pkt.command.toString(16)}")
                    }
                }
            }
        }

        internal fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xFF)
            return sum
        }

        internal fun encodeHeader(packet: Packet): ByteArray {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(packet.command)
            buf.putInt(packet.arg0)
            buf.putInt(packet.arg1)
            buf.putInt(packet.payload.size)
            buf.putInt(checksum(packet.payload))
            buf.putInt(packet.command xor -1)
            return buf.array()
        }

        internal fun writePacket(out: OutputStream, packet: Packet) {
            out.write(encodeHeader(packet))
            if (packet.payload.isNotEmpty()) out.write(packet.payload)
            out.flush()
        }

        internal fun readPacket(input: InputStream, negotiatedVersion: Int): Packet {
            val hdr = readFully(input, 24)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val command = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val dataLen = bb.int
            val dataCheck = bb.int
            val magic = bb.int

            if (magic != (command xor -1)) {
                throw IOException("ADB 数据包校验魔数不匹配（可能对方端口不是 ADB 服务）")
            }
            if (dataLen !in 0..MAX_FRAME_PAYLOAD) {
                throw IOException("ADB 数据包长度异常：$dataLen")
            }
            val payload = if (dataLen == 0) EMPTY else readFully(input, dataLen)
            val skipChecksum = dataCheck == 0 && negotiatedVersion >= ADB_VERSION_SKIP_CHECKSUM
            if (!skipChecksum && checksum(payload) != dataCheck) {
                throw IOException("ADB 数据包内容校验和失败")
            }
            return Packet(command, arg0, arg1, payload)
        }

        private fun readFully(input: InputStream, length: Int): ByteArray {
            val out = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(out, offset, length - offset)
                if (n < 0) throw EOFException("ADB 连接已关闭")
                offset += n
            }
            return out
        }
    }
}
