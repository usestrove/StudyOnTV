package com.studyontv.tv

import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * 仅本地隐私版（EU Local-Only）网络边界守卫（Issue #175）。
 *
 * 当 BuildConfig.LOCAL_ONLY 为 true 时：
 * - 禁用所有崩溃/运行日志上报与在线更新检查/安装；
 * - 禁用在线题库市场与节假日联网同步；
 * - AI 与朗读只允许本机或家庭局域网服务（如 Ollama / 本地系统语音）；
 * - WebView 拦截所有公网子资源请求。
 */
object LocalOnlyGuard {
    val enabled: Boolean get() = BuildConfig.LOCAL_ONLY

    fun isLocalHost(rawHost: String?): Boolean {
        val host = rawHost?.trim()?.trimStart('[')?.trimEnd(']')?.lowercase(Locale.US).orEmpty()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        // 纯 IPv4 快速判断（不触发 DNS 查询）
        val ipv4 = host.split('.')
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4[0].toInt()
            val b = ipv4[1].toInt()
            if (a == 127 || a == 10) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 169 && b == 254) return true
            return false
        }
        return try {
            val addrs = InetAddress.getAllByName(host)
            addrs.isNotEmpty() && addrs.all { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }
        } catch (_: Exception) {
            false
        }
    }

    fun isLocalUrl(rawUrl: String?): Boolean {
        val u = rawUrl?.trim().orEmpty()
        if (u.isEmpty()) return true
        val lower = u.lowercase(Locale.US)
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:") ||
            lower.startsWith("file:") || lower.startsWith("content:")
        ) return true
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ws://") || lower.startsWith("wss://"))) {
            return false
        }
        val normalized = when {
            lower.startsWith("ws://") -> "http://" + u.substring(5)
            lower.startsWith("wss://") -> "https://" + u.substring(6)
            else -> u
        }
        return try {
            isLocalHost(URL(normalized).host)
        } catch (_: Exception) {
            false
        }
    }

    fun requireLocalUrl(rawUrl: String, purpose: String) {
        if (enabled && !isLocalUrl(rawUrl)) {
            throw IllegalArgumentException("仅本地隐私版（EU Local-Only）仅允许连接本机或局域网内的$purpose")
        }
    }
}
