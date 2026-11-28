package com.studyontv.tv

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.URI
import java.util.Locale

/**
 * 局域网 HTTP 服务的访问控制与编解码策略：
 * 包含私有网段 CORS 校验、三类令牌路由隔离、孩子切换密码裁决、路径段解码与同步结果文案。
 */
internal object LanAccessPolicy {
    /** 按 RFC 3986 路径段规则解码 UTF-8 百分号编码（保留字面 `+`，拒绝非法 `%` 序列）。 */
    fun decodePathSegment(raw: String): String {
        if ('%' !in raw) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%') {
                require(i + 2 < raw.length) { "路径参数编码无效" }
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                require(hi in 0..15 && lo in 0..15) { "路径参数编码无效" }
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                if (ch.code < 0x80) out.write(ch.code)
                else out.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 严格按 Content-Length 读取完整请求体；若客户端中途断连导致 EOF 则抛 EOFException 中止请求。 */
    fun readExactBody(input: InputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) throw EOFException("请求体未接收完整（已读 $read / $length 字节）")
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    /**
     * 仅允许本机或局域网私有地址来源的跨域请求，防止公网网页通过 Private Network Access 探测电视接口。
     * 只做纯语法校验，绝不对未知域名发起 DNS 解析（防止 DNS Rebinding 与阻塞）。
     */
    fun allowedCorsOrigin(origin: String?): String? {
        if (origin == null || origin.isBlank()) return "*"
        val trimmed = origin.trim()
        if (trimmed.equals("null", ignoreCase = true)) return null
        val uri = try { URI(trimmed) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null || (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }
        val host = uri.host?.lowercase(Locale.US)?.removeSurrounding("[", "]") ?: return null
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan")) {
            return trimmed
        }
        if (isPrivateIpv4Literal(host)) return trimmed
        return null
    }

    private fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
            octets[i] = v
        }
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    /** 设备令牌（学习页 WebView）仅允许调用学习状态、切换孩子与答题相关接口，禁止改动规则、题库或密码。 */
    fun isDeviceRouteAllowed(method: String, path: String): Boolean = when {
        method == "GET" && (path == "/api/state" || path == "/api/learning/state") -> true
        method == "POST" && path in setOf(
            "/api/children/switch",
            "/api/quiz/start",
            "/api/quiz/answer",
            "/api/quiz/continue",
            "/api/quiz/review",
            "/api/quiz/skip",
            "/api/quiz/memorized",
            "/api/quiz/rate",
            "/api/event"
        ) -> true
        else -> false
    }

    /** 解析可选 pin 字段：未传或 null 返回 null（不改动）；传 "" 表示清空；非空返回字符串。 */
    fun extractOptionalPin(input: JSONObject): String? =
        if (input.has("pin") && !input.isNull("pin")) input.optString("pin", "") else null

    /**
     * 评估孩子切换权限（ST-610）：
     * 家长会话、同孩子或未设密码直接放行；学习页切换已设密码的孩子需提供切换密码或家长主密码。
     */
    fun evaluateChildSwitch(
        targetId: String,
        activeId: String,
        isParent: Boolean,
        targetHasPin: Boolean,
        pin: String?,
        verifyChildPin: (String) -> Boolean,
        verifyParentPassword: (String) -> Boolean
    ): LanServer.Companion.ChildSwitchDecision {
        if (isParent || targetId == activeId || !targetHasPin) {
            return LanServer.Companion.ChildSwitchDecision(allowed = true)
        }
        val candidate = pin?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return LanServer.Companion.ChildSwitchDecision(
                allowed = false,
                errorCode = "child_pin_required",
                message = "请输入该孩子的切换密码"
            )
        }
        if (verifyChildPin(candidate) || verifyParentPassword(candidate)) {
            return LanServer.Companion.ChildSwitchDecision(allowed = true)
        }
        return LanServer.Companion.ChildSwitchDecision(
            allowed = false,
            errorCode = "child_pin_invalid",
            message = "切换密码错误，请重试"
        )
    }

    /** 统一 `/api/` 接口鉴权裁决：返回 `null` 表示放行；否则返回 `(httpStatus, message)`。 */
    fun checkApiAccess(
        method: String,
        path: String,
        deviceAccess: Boolean,
        parent: Boolean,
        integration: IntegrationAuth.Scope?
    ): Pair<Int, String>? {
        val isPublicApi = path == "/api/hello" || (method == "GET" && path.startsWith(MediaStore.PREFIX))
        if (!path.startsWith("/api/") || isPublicApi) return null
        if (!deviceAccess && !parent && integration == null) return 401 to "unauthorized"
        if (integration != null && !deviceAccess && !parent) {
            val scope = when {
                path == "/api/state" && method == "GET" -> "read"
                path == "/api/device" && method == "GET" -> "read"
                path == "/api/coverage" && method == "GET" -> "read"
                path == "/api/analytics" && method == "GET" -> "read"
                path == "/api/remote" && method == "POST" -> "control"
                else -> null
            } ?: return 403 to "这个接口需要家长登录"
            if ((scope == "read" && !integration.read) || (scope == "control" && !integration.control)) {
                return 403 to "集成令牌没有这个权限"
            }
        }
        if (deviceAccess && !parent && integration == null && !isDeviceRouteAllowed(method, path)) {
            return 403 to "forbidden"
        }
        return null
    }

    fun formatSyncSuccessMessage(
        scopes: Set<String>,
        mode: String,
        syncedQuestions: Int,
        totalQuestions: Int
    ): String {
        val parts = mutableListOf<String>()
        if ("config" in scopes) parts.add("学习计划")
        if ("questions" in scopes) {
            val modeLabel = if (mode == "replace") "覆盖" else "合并"
            val count = if (mode == "replace") totalQuestions else syncedQuestions
            parts.add("题库（$modeLabel ${count} 题）")
        }
        if ("speech" in scopes) parts.add("朗读设置")
        if ("grading" in scopes) parts.add("AI 模型配置")
        return if (parts.isEmpty()) "同步完成" else "已同步" + parts.joinToString("、")
    }
}
