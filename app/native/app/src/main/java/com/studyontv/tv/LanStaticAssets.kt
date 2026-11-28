package com.studyontv.tv

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * 局域网静态资源服务：负责学习页与家长面板构建产物的路径映射、ETag 协商与 Gzip 内存缓存。
 */
internal class LanStaticAssets(
    private val context: Context,
    private val assets: AssetManager
) {
    private val gzipCache = ConcurrentHashMap<String, ByteArray>()
    private val etag = "\"" + BuildConfig.VERSION_CODE + "-" +
        (try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }) + "\""

    fun serve(
        server: LanServer,
        path: String,
        out: OutputStream,
        gzip: Boolean,
        ifNoneMatch: String?,
        local: Boolean,
        origin: String?
    ) {
        val (assetPath, contentType) = when {
            path == "/practice" || path == "/practice.html" -> "web/practice.html" to "text/html; charset=utf-8"
            path == "/practice-sw.js" -> "web/practice-sw.js" to "application/javascript; charset=utf-8"
            path == "/practice.appcache" -> "web/practice.appcache" to "text/cache-manifest; charset=utf-8"
            path == "/" || path == "/index.html" -> if (local) "web/index.html" to "text/html; charset=utf-8"
            else "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/admin/assets/") || path.startsWith("/admin/icons/") ||
                path == "/admin/manifest.webmanifest" || path == "/admin/sw.js" -> "admin" + path.removePrefix("/admin") to mime(path)
            path == "/admin" -> return server.reply(out, 301, null, null, mapOf("Location" to "/admin/"), origin)
            path.startsWith("/admin") -> "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/assets/") -> "web" + path to mime(path)
            else -> if (local) "web/index.html" to "text/html; charset=utf-8" else "admin/index.html" to "text/html; charset=utf-8"
        }
        if (assetPath.contains("..")) return server.reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = origin)
        val hashed = Regex("-[A-Za-z0-9_-]{8}\\.(js|css)$").containsMatchIn(assetPath) || assetPath.contains("/vendor/")
        val cache = if (hashed) "public, max-age=31536000, immutable" else "no-cache"
        if (ifNoneMatch == etag) return server.reply(out, 304, null, null, mapOf("ETag" to etag, "Cache-Control" to cache), origin)
        try {
            val compressible = contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json") || contentType.contains("svg")
            val extra = mutableMapOf("ETag" to etag, "Cache-Control" to cache)
            val bytes = if (gzip && compressible) {
                extra["Content-Encoding"] = "gzip"; extra["Vary"] = "Accept-Encoding"
                gzipCache.getOrPut(assetPath) {
                    val raw = assetBytes(assetPath)
                    ByteArrayOutputStream().also { buf -> GZIPOutputStream(buf).use { it.write(raw) } }.toByteArray()
                }
            } else assetBytes(assetPath)
            server.reply(out, 200, contentType, bytes, extra, origin)
        } catch (_: Exception) {
            server.reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = origin)
        }
    }

    private fun assetBytes(assetPath: String): ByteArray {
        val raw = assets.open(assetPath).use { it.readBytes() }
        if (assetPath != "admin/index.html") return raw
        val text = String(raw, Charsets.UTF_8)
        if (text.contains("<base ")) return raw
        return text.replaceFirst("<head>", "<head><base href=\"/admin/\">").toByteArray(Charsets.UTF_8)
    }

    private fun mime(path: String): String = when {
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".webmanifest") -> "application/manifest+json; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
