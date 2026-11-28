package com.studyontv.parent

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * 纯 Kotlin 实现的 ADB RSA-2048 密钥管理与认证原语：
 * - 公钥格式严格对齐 AOSP `android_pubkey.c`（524 字节小端 RSAPublicKey + Base64 + ` user@host\0`）；
 * - 令牌签名严格对齐 `adbd` 的 `RSA_verify(NID_sha1, token, 20, sig)`：使用 `NONEwithRSA` 对
 *   `SHA1_DIGEST_INFO_PREFIX || token(20B)` 做 PKCS#1 v1.5 Type-1 签名（切勿用 `SHA1withRSA` 二次哈希）。
 */
class AdbCrypto(
    val keyPair: KeyPair,
    val userHost: String = DEFAULT_USER_HOST
) {
    val privateKey: RSAPrivateKey = keyPair.private as RSAPrivateKey
    val publicKey: RSAPublicKey = keyPair.public as RSAPublicKey

    val androidPublicKey: ByteArray by lazy {
        formatAndroidPublicKey(publicKey, userHost)
    }

    fun signToken(token: ByteArray): ByteArray = signToken(privateKey, token)

    companion object {
        const val DEFAULT_USER_HOST = "parent@studyontv"
        const val ANDROID_PUBKEY_MODULUS_SIZE = 256
        const val ANDROID_PUBKEY_MODULUS_WORDS = 64
        const val ANDROID_PUBKEY_STRUCT_BYTES = 4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE * 2 + 4 // 524 bytes

        // ASN.1 DER DigestInfo 前缀：SEQUENCE { AlgorithmIdentifier(sha1WithRSA), OCTET STRING(20) }
        internal val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        private const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        fun loadOrCreate(context: Context, userHost: String = DEFAULT_USER_HOST): AdbCrypto {
            val dir = File(context.filesDir, "adb_key").apply { mkdirs() }
            val privFile = File(dir, "adb_rsa.pkcs8")
            val pubFile = File(dir, "adb_rsa.x509")
            val existing = tryLoad(privFile, pubFile)
            if (existing != null) return AdbCrypto(existing, userHost)

            val generated = generateKeyPair()
            try {
                privFile.writeBytes(generated.private.encoded)
                privFile.setReadable(false, false)
                privFile.setReadable(true, true)
                privFile.setWritable(false, false)
                privFile.setWritable(true, true)

                pubFile.writeBytes(generated.public.encoded)
                pubFile.setReadable(false, false)
                pubFile.setReadable(true, true)
            } catch (_: Exception) {
                // 即使落盘失败，也仍可用内存密钥完成本次配网
            }
            return AdbCrypto(generated, userHost)
        }

        fun generateKeyPair(): KeyPair {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            return gen.generateKeyPair()
        }

        private fun tryLoad(privFile: File, pubFile: File): KeyPair? {
            if (!privFile.isFile || !pubFile.isFile) return null
            return try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())) as RSAPrivateKey
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                if (pub.modulus.bitLength() != 2048) return null
                KeyPair(pub, priv)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 构造 524 字节 AOSP `RSAPublicKey` 小端结构体，供 `AUTH_RSAPUBLICKEY` 编码与单测校验。
         */
        internal fun encodeAndroidPublicKeyStruct(pub: RSAPublicKey): ByteArray {
            val modulus = pub.modulus
            require(modulus.bitLength() == 2048) { "ADB 仅支持 2048 位 RSA 公钥（当前 ${modulus.bitLength()} 位）" }
            val exponent = pub.publicExponent.toInt()
            require(exponent > 0) { "无效的 RSA 公钥指数" }

            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0 = modulus.mod(r32)
            val n0inv = r32.subtract(n0.modInverse(r32))
            val r = BigInteger.ONE.shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 8)
            val rr = r.multiply(r).mod(modulus)

            val buf = ByteBuffer.allocate(ANDROID_PUBKEY_STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(ANDROID_PUBKEY_MODULUS_WORDS)
            buf.putInt(n0inv.toInt())
            buf.put(toLittleEndianBytes(modulus, ANDROID_PUBKEY_MODULUS_SIZE))
            buf.put(toLittleEndianBytes(rr, ANDROID_PUBKEY_MODULUS_SIZE))
            buf.putInt(exponent)
            return buf.array()
        }

        /**
         * 构造完整的 ADB `AUTH_RSAPUBLICKEY` 负载：`<base64(524B)> <userHost>\0`。
         */
        fun formatAndroidPublicKey(pub: RSAPublicKey, userHost: String = DEFAULT_USER_HOST): ByteArray {
            val structBytes = encodeAndroidPublicKeyStruct(pub)
            val b64 = encodeBase64NoWrap(structBytes)
            val suffix = " ${userHost.trim().ifEmpty { DEFAULT_USER_HOST }}\u0000"
            return (b64 + suffix).toByteArray(Charsets.UTF_8)
        }

        /**
         * 对 `adbd` 下发的 20 字节 `AUTH_TOKEN` 进行 PKCS#1 v1.5 SHA-1 DigestInfo 签名。
         */
        fun signToken(priv: RSAPrivateKey, token: ByteArray): ByteArray {
            require(token.size == 20) { "ADB AUTH_TOKEN 长度应为 20 字节，实际 ${token.size}" }
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initSign(priv)
            sig.update(SHA1_DIGEST_INFO_PREFIX)
            sig.update(token)
            return sig.sign()
        }

        internal fun toLittleEndianBytes(value: BigInteger, length: Int): ByteArray {
            val out = ByteArray(length)
            val be = value.toByteArray()
            var src = be.size - 1
            var dst = 0
            while (src >= 0 && dst < length) {
                out[dst++] = be[src--]
            }
            return out
        }

        /** 纯 Kotlin RFC 4648 Base64（无换行），兼容 Android API 21+ 与纯 JVM 单元测试。 */
        internal fun encodeBase64NoWrap(input: ByteArray): String {
            if (input.isEmpty()) return ""
            val sb = StringBuilder(((input.size + 2) / 3) * 4)
            var i = 0
            while (i < input.size) {
                val b0 = input[i].toInt() and 0xFF
                val has1 = i + 1 < input.size
                val has2 = i + 2 < input.size
                val b1 = if (has1) input[i + 1].toInt() and 0xFF else 0
                val b2 = if (has2) input[i + 2].toInt() and 0xFF else 0

                sb.append(BASE64_ALPHABET[b0 ushr 2])
                sb.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(if (has1) BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
                sb.append(if (has2) BASE64_ALPHABET[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }
    }
}
