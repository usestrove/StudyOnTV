package com.studyontv.parent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbCryptoTest {

    @Test
    fun androidPublicKeyStructMatchesAospBinarySpec() {
        val kp = AdbCrypto.generateKeyPair()
        val pub = kp.public as RSAPublicKey

        val struct = AdbCrypto.encodeAndroidPublicKeyStruct(pub)
        assertEquals(524, struct.size)

        val bb = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        val words = bb.int
        val n0invSigned = bb.int
        val modLe = ByteArray(256).also { bb.get(it) }
        val rrLe = ByteArray(256).also { bb.get(it) }
        val exponent = bb.int

        assertEquals(64, words)
        assertEquals(pub.publicExponent.toInt(), exponent)

        // 校验 n0inv：-(N^-1) mod 2^32  <=>  (n0inv * N) mod 2^32 == 2^32 - 1
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0invUnsigned = BigInteger.valueOf(n0invSigned.toLong() and 0xFFFF_FFFFL)
        assertEquals(r32.subtract(BigInteger.ONE), n0invUnsigned.multiply(pub.modulus).mod(r32))

        // 校验小端 modulus 与 rr 数值
        assertEquals(pub.modulus, BigInteger(1, modLe.reversedArray()))
        val expectedRr = BigInteger.ONE.shiftLeft(4096).mod(pub.modulus)
        assertEquals(expectedRr, BigInteger(1, rrLe.reversedArray()))
    }

    @Test
    fun formatAndroidPublicKeyEncodesBase64AndUserHostWithNul() {
        val kp = AdbCrypto.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val formatted = AdbCrypto.formatAndroidPublicKey(pub, "parent@studyontv")

        assertEquals(0.toByte(), formatted.last())
        val text = String(formatted, 0, formatted.size - 1, Charsets.UTF_8)
        assertTrue(text.endsWith(" parent@studyontv"))

        val b64Part = text.substringBefore(' ')
        assertTrue(!b64Part.contains('\n') && !b64Part.contains('\r'))
        val decoded = Base64.getDecoder().decode(b64Part)
        assertArrayEquals(AdbCrypto.encodeAndroidPublicKeyStruct(pub), decoded)
    }

    @Test
    fun signTokenProducesValidSha1Pkcs1v15SignatureForAdbd() {
        val kp = AdbCrypto.generateKeyPair()
        val priv = kp.private as RSAPrivateKey
        val pub = kp.public as RSAPublicKey

        val challenge = "studyontv-adb-auth-token-verification".toByteArray(Charsets.UTF_8)
        val sha1Digest = MessageDigest.getInstance("SHA-1").digest(challenge)
        assertEquals(20, sha1Digest.size)

        val signature = AdbCrypto.signToken(priv, sha1Digest)
        assertEquals(256, signature.size)

        // adbd 内部用 RSA_verify(NID_sha1, token, 20, sig) 验签，
        // 与标准 JCA SHA1withRSA 对原文 challenge 验签在数学上完全等价！
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(pub)
        verifier.update(challenge)
        assertTrue("signToken 签名必须能通过标准 SHA1withRSA 验签", verifier.verify(signature))
    }

    @Test
    fun signTokenRejectsNon20ByteToken() {
        val kp = AdbCrypto.generateKeyPair()
        val priv = kp.private as RSAPrivateKey
        try {
            AdbCrypto.signToken(priv, ByteArray(16))
            fail("期望抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
