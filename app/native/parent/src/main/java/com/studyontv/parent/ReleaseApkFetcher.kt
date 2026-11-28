package com.studyontv.parent

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * 为无线 ADB 一键装机获取受控端最新 APK：
 * 1. 优先查蒲公英公开检查接口（国内网络直连快）；
 * 2. 回退到 GitHub `update.json`（jsDelivr CDN + raw.githubusercontent）；
 * 3. 支持家长直接选取手机里已下载好的本地 `.apk` 文件（零网络依赖）。
 */
object ReleaseApkFetcher {
    private const val PGYER_CHECK_URL = "https://www.pgyer.com/apiv1/update/check"
    private const val PGYER_SDK_API_KEY = "305092bc73c180b55c26012a94809131"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; StudyOnTV-Parent) AppleWebKit/537.36"
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private const val MAX_REDIRECTS = 6

    private val MANIFEST_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/chadyi/StudyOnTV@main/update.json",
        "https://raw.githubusercontent.com/chadyi/StudyOnTV/main/update.json"
    )

    data class ReleaseMeta(
        val flavor: TvProvisioner.TargetFlavor,
        val version: String,
        val downloadUrl: String,
        val sha256: String,
        val expectedSize: Long,
        val source: String
    )

    fun fetchLatestMeta(flavor: TvProvisioner.TargetFlavor): ReleaseMeta {
        val errors = ArrayList<String>()
        try {
            fetchFromPgyer(flavor)?.let { return it }
        } catch (e: Exception) {
            errors.add("蒲公英：${e.message}")
        }
        for (url in MANIFEST_URLS) {
            try {
                fetchFromManifest(url, flavor)?.let { return it }
            } catch (e: Exception) {
                errors.add("${URL(url).host}：${e.message}")
            }
        }
        throw IOException(
            "无法在线获取最新安装包（${errors.joinToString("；").take(160)}）。" +
                "建议直接点击下方「选择手机本地 APK」安装。"
        )
    }

    private fun fetchFromPgyer(flavor: TvProvisioner.TargetFlavor): ReleaseMeta? {
        val form = listOf(
            "_api_key" to PGYER_SDK_API_KEY,
            "agKey" to flavor.pgyerKey,
            "version" to "0.0.0",
            "build" to "0",
            "sdkapi" to "30"
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val conn = (URL(PGYER_CHECK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) {
                throw IOException(root.optString("message").ifEmpty { "接口返回失败" })
            }
            val data = root.optJSONObject("data") ?: return null
            val url = data.optString("downloadURL").trim().replaceFirst("http://", "https://")
            if (!url.startsWith("https://www.pgyer.com/app/installUpdate/")) return null
            val version = data.optString("version").trim().removePrefix("v").ifEmpty { "latest" }
            ReleaseMeta(
                flavor = flavor,
                version = version,
                downloadUrl = url,
                sha256 = data.optString("sha256").trim().lowercase(Locale.ROOT),
                expectedSize = 0L,
                source = "蒲公英（国内通道）"
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFromManifest(manifestUrl: String, flavor: TvProvisioner.TargetFlavor): ReleaseMeta? {
        val conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(text)
            val version = root.optString("version").trim().removePrefix("v").ifEmpty { "latest" }
            val asset = root.optJSONObject("assets")?.optJSONObject(flavor.manifestKey) ?: return null
            val dl = asset.optString("url").trim()
            if (!dl.startsWith("https://")) return null
            ReleaseMeta(
                flavor = flavor,
                version = version,
                downloadUrl = dl,
                sha256 = asset.optString("sha256").trim().lowercase(Locale.ROOT),
                expectedSize = asset.optLong("size", 0L).coerceAtLeast(0L),
                source = "GitHub Release"
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载并缓存目标 APK，校验 ZIP 魔数与可选 SHA-256。
     */
    fun downloadApk(
        context: Context,
        meta: ReleaseMeta,
        onProgress: (percent: Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val safeVersion = meta.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "studyontv-${meta.flavor.manifestKey}-v$safeVersion.apk")

        if (isValidCachedApk(target, meta.sha256, meta.expectedSize)) {
            onProgress(100)
            return target
        }

        val tmp = File(dir, "${target.name}.downloading")
        if (tmp.exists()) tmp.delete()

        var currentUrl = meta.downloadUrl
        var redirects = 0
        var conn: HttpURLConnection? = null
        try {
            while (true) {
                val u = URL(currentUrl)
                require(u.protocol == "https") { "仅允许通过 HTTPS 下载安装包" }
                val c = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 25_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location").orEmpty().trim()
                    c.disconnect()
                    if (loc.isEmpty() || ++redirects > MAX_REDIRECTS) {
                        throw IOException("下载重定向异常（HTTP $code）")
                    }
                    currentUrl = URL(u, loc).toString()
                    continue
                }
                if (code !in 200..299) {
                    c.disconnect()
                    throw IOException("安装包下载失败（HTTP $code）")
                }
                conn = c
                break
            }

            val active = conn!!
            val total = active.contentLengthLong.takeIf { it > 0L } ?: meta.expectedSize
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastPct = -1
            val buf = ByteArray(16 * 1024)

            active.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        written += n
                        if (written > MAX_APK_BYTES) {
                            throw IOException("安装包体积超过 150MB 限制，已中止")
                        }
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        if (total > 0L) {
                            val pct = ((written * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            if (written < 1024L || !hasZipMagic(tmp)) {
                throw IOException("下载到的文件不是有效 APK 安装包")
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (meta.sha256.length == 64 && !actualSha.equals(meta.sha256, ignoreCase = true)) {
                throw IOException("安装包 SHA-256 校验不通过，请重试")
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            onProgress(100)
            return target
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /** 将家长通过系统文件选择器选中的本地 APK 拷贝到缓存并做基本校验。 */
    fun copyLocalApk(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val dst = File(dir, "local-selected.apk")
        val tmp = File(dir, "local-selected.apk.tmp")
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取选中的 APK 文件")
            var total = 0L
            val buf = ByteArray(16 * 1024)
            stream.use { input ->
                FileOutputStream(tmp).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("文件超过 150MB 限制")
                        output.write(buf, 0, n)
                    }
                }
            }
            if (total < 1024L || !hasZipMagic(tmp)) {
                throw IOException("所选文件不是有效的 Android APK 安装包")
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            return dst
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun isValidCachedApk(file: File, expectedSha256: String, expectedSize: Long): Boolean {
        if (!file.isFile || file.length() < 1024L || !hasZipMagic(file)) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        if (expectedSha256.length == 64) {
            val sha = sha256Hex(file)
            if (!sha.equals(expectedSha256, ignoreCase = true)) return false
        }
        return true
    }

    internal fun hasZipMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        val header = ByteArray(4)
        FileInputStream(file).use { fis ->
            if (fis.read(header) != 4) return false
        }
        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
