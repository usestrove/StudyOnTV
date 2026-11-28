package com.studyontv.parent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher

class AdbClientProtocolTest {

    private class PipePair {
        val clientToServerOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServerOut, 64 * 1024)
        val serverToClientOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClientOut, 64 * 1024)

        val clientTransport = object : AdbClient.Transport {
            override val input: InputStream = clientIn
            override val output: OutputStream = clientToServerOut
            override fun setSoTimeout(timeoutMs: Int) {}
            override fun close() {
                try { clientIn.close() } catch (_: Exception) {}
                try { clientToServerOut.close() } catch (_: Exception) {}
            }
        }
    }

    @Test
    fun packetHeaderAndUnsignedChecksumRoundTrip() {
        val payload = byteArrayOf(0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        // 无符号求和：1 + 127 + 128 + 255 = 511
        assertEquals(511, AdbClient.checksum(payload))

        val out = ByteArrayOutputStream()
        AdbClient.writePacket(out, AdbClient.Packet(AdbClient.A_WRTE, 7, 9, payload))
        val parsed = AdbClient.readPacket(ByteArrayInputStream(out.toByteArray()), AdbClient.ADB_VERSION)

        assertEquals(AdbClient.A_WRTE, parsed.command)
        assertEquals(7, parsed.arg0)
        assertEquals(9, parsed.arg1)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun readPacketRejectsCorruptMagicAndBadChecksum() {
        val good = AdbClient.encodeHeader(AdbClient.Packet(AdbClient.A_CNXN, 1, 2, ByteArray(0)))
        val badMagic = good.copyOf()
        badMagic[20] = (badMagic[20].toInt() xor 0xFF).toByte()
        try {
            AdbClient.readPacket(ByteArrayInputStream(badMagic), AdbClient.ADB_VERSION)
            fail("魔数损坏应抛 IOException")
        } catch (_: IOException) {
            // expected
        }

        val withData = ByteArrayOutputStream()
        AdbClient.writePacket(withData, AdbClient.Packet(AdbClient.A_WRTE, 1, 2, "abc".toByteArray()))
        val corrupted = withData.toByteArray()
        corrupted[corrupted.size - 1] = 'z'.code.toByte()
        try {
            AdbClient.readPacket(ByteArrayInputStream(corrupted), AdbClient.ADB_VERSION)
            fail("校验和不匹配应抛 IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun handshakeTrustedSignatureThenShellCommand() {
        val crypto = AdbCrypto(AdbCrypto.generateKeyPair())
        val pipes = PipePair()
        val waitingCalled = AtomicBoolean(false)
        val serverErr = AtomicReference<Throwable?>(null)

        val server = Thread {
            try {
                // 1. 收 CNXN
                val cnxn = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals(AdbClient.A_CNXN, cnxn.command)

                // 2. 发 20 字节 AUTH_TOKEN
                val token = ByteArray(20) { (it + 11).toByte() }
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, token))

                // 3. 收 AUTH_SIGNATURE 并用公钥解包校验 DigestInfo + token
                val auth = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals(AdbClient.A_AUTH, auth.command)
                assertEquals(AdbClient.AUTH_SIGNATURE, auth.arg0)
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.DECRYPT_MODE, crypto.publicKey)
                val decrypted = cipher.doFinal(auth.payload)
                assertArrayEquals(AdbCrypto.SHA1_DIGEST_INFO_PREFIX + token, decrypted)

                // 4. 回复 CNXN
                AdbClient.writePacket(
                    pipes.serverToClientOut,
                    AdbClient.Packet(AdbClient.A_CNXN, AdbClient.ADB_VERSION, 65536, "device::model=MockTV\u0000".toByteArray())
                )

                // 5. 处理 shell 命令
                val open = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals(AdbClient.A_OPEN, open.command)
                assertEquals("shell:getprop ro.product.model\u0000", String(open.payload, Charsets.UTF_8))
                val clientLocalId = open.arg0
                val remoteId = 42
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_OKAY, remoteId, clientLocalId, ByteArray(0)))
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_WRTE, remoteId, clientLocalId, "MiTV-".toByteArray()))
                assertEquals(AdbClient.A_OKAY, AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION).command)
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_WRTE, remoteId, clientLocalId, "4S\n".toByteArray()))
                assertEquals(AdbClient.A_OKAY, AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION).command)
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_CLSE, remoteId, clientLocalId, ByteArray(0)))
                assertEquals(AdbClient.A_CLSE, AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION).command)
            } catch (t: Throwable) {
                serverErr.set(t)
            }
        }
        server.start()

        AdbClient.handshake(pipes.clientTransport, crypto, onWaitingForApproval = { waitingCalled.set(true) }).use { client ->
            assertFalse("已授信密钥不应弹出电视授权等待", waitingCalled.get())
            assertEquals("device::model=MockTV", client.banner)
            assertEquals("MiTV-4S\n", client.shell("getprop ro.product.model"))
        }
        server.join(5000)
        serverErr.get()?.let { throw it }
    }

    @Test
    fun handshakeUntrustedPublicKeyAndSmallPayloadSyncPush() {
        val crypto = AdbCrypto(AdbCrypto.generateKeyPair())
        val pipes = PipePair()
        val waitingCalled = AtomicBoolean(false)
        val serverErr = AtomicReference<Throwable?>(null)
        val receivedFileBytes = ByteArrayOutputStream()
        val receivedSpec = AtomicReference<String>("")

        // 20KB 随机测试文件，且模拟老款 Android 5 电视 peerMaxPayload = 4096
        val sampleData = ByteArray(20_500).also { Random(2026).nextBytes(it) }

        val server = Thread {
            try {
                AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                // 第一次发 token，拒绝客户端签名，再发一次 token 触发客户端发送公钥
                val token1 = ByteArray(20) { 1 }
                val token2 = ByteArray(20) { 2 }
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, token1))
                val sigPkt = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals(AdbClient.AUTH_SIGNATURE, sigPkt.arg0)

                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, token2))
                val pubPkt = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals(AdbClient.AUTH_RSAPUBLICKEY, pubPkt.arg0)
                assertArrayEquals(crypto.androidPublicKey, pubPkt.payload)

                // 模拟用户在电视上点了「一律允许」，回复 CNXN（maxPayload = 4096）
                AdbClient.writePacket(
                    pipes.serverToClientOut,
                    AdbClient.Packet(AdbClient.A_CNXN, AdbClient.ADB_VERSION, 4096, "device::oldtv\u0000".toByteArray())
                )

                // 接收 sync: 推送
                val open = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                assertEquals("sync:\u0000", String(open.payload, Charsets.UTF_8))
                val clientLocalId = open.arg0
                val remoteId = 99
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_OKAY, remoteId, clientLocalId, ByteArray(0)))

                val syncStream = ByteArrayOutputStream()
                var sawDone = false
                while (!sawDone) {
                    val pkt = AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION)
                    assertEquals(AdbClient.A_WRTE, pkt.command)
                    assertTrue("单帧负载不得超过老电视 4096 字节上限", pkt.payload.size <= 4096)
                    AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_OKAY, remoteId, clientLocalId, ByteArray(0)))
                    syncStream.write(pkt.payload)

                    // 检查是否已解析完 DONE
                    val bytes = syncStream.toByteArray()
                    val parsedDone = parseSyncFrames(bytes, receivedSpec, receivedFileBytes)
                    if (parsedDone) {
                        sawDone = true
                    }
                }

                // 回复 SYNC OKAY
                val okayReply = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .put("OKAY".toByteArray(Charsets.US_ASCII))
                    .putInt(0)
                    .array()
                AdbClient.writePacket(pipes.serverToClientOut, AdbClient.Packet(AdbClient.A_WRTE, remoteId, clientLocalId, okayReply))
                assertEquals(AdbClient.A_OKAY, AdbClient.readPacket(pipes.serverIn, AdbClient.ADB_VERSION).command)
            } catch (t: Throwable) {
                serverErr.set(t)
            }
        }
        server.start()

        val progressMarks = ArrayList<Int>()
        AdbClient.handshake(pipes.clientTransport, crypto, onWaitingForApproval = { waitingCalled.set(true) }).use { client ->
            assertTrue("首次连接未授信电视必须回调提醒用户点确认", waitingCalled.get())
            assertEquals(4096, client.peerMaxPayload)
            client.pushStream(
                source = ByteArrayInputStream(sampleData),
                totalBytes = sampleData.size.toLong(),
                remotePath = TvProvisioner.REMOTE_TMP_APK,
                onProgress = { progressMarks.add(it) }
            )
        }
        server.join(5000)
        serverErr.get()?.let { throw it }

        assertEquals("${TvProvisioner.REMOTE_TMP_APK},33188", receivedSpec.get())
        assertArrayEquals(sampleData, receivedFileBytes.toByteArray())
        assertTrue(progressMarks.contains(100))
    }

    private fun parseSyncFrames(
        allBytes: ByteArray,
        specOut: AtomicReference<String>,
        fileOut: ByteArrayOutputStream
    ): Boolean {
        val tempFile = ByteArrayOutputStream()
        var pos = 0
        while (pos + 8 <= allBytes.size) {
            val tag = String(allBytes, pos, 4, Charsets.US_ASCII)
            val arg = ByteBuffer.wrap(allBytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            pos += 8
            when (tag) {
                "SEND" -> {
                    if (pos + arg > allBytes.size) return false
                    specOut.set(String(allBytes, pos, arg, Charsets.UTF_8))
                    pos += arg
                }
                "DATA" -> {
                    if (pos + arg > allBytes.size) return false
                    tempFile.write(allBytes, pos, arg)
                    pos += arg
                }
                "DONE" -> {
                    fileOut.reset()
                    fileOut.write(tempFile.toByteArray())
                    return true
                }
                else -> return false
            }
        }
        return false
    }
}
