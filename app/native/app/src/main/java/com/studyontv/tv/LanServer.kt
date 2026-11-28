package com.studyontv.tv

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

class LanServer(
    internal val context: Context,
    internal val store: LocalStore,
    private val assets: AssetManager,
    private val port: Int = 8099
) {
    companion object {
        private const val MAX_UNAUTH_BODY = 4_096
        private const val MAX_INTEGRATION_BODY = 65_536
        private const val MAX_DEVICE_BODY = 3_000_000
        private const val MAX_PARENT_BODY = 24_000_000

        internal data class ChildSwitchDecision(
            val allowed: Boolean,
            val errorCode: String? = null,
            val message: String? = null
        )

        internal fun decodePathSegment(raw: String): String = LanAccessPolicy.decodePathSegment(raw)
        internal fun readExactBody(input: InputStream, length: Int): String = LanAccessPolicy.readExactBody(input, length)
        internal fun allowedCorsOrigin(origin: String?): String? = LanAccessPolicy.allowedCorsOrigin(origin)
        internal fun isDeviceRouteAllowed(method: String, path: String): Boolean = LanAccessPolicy.isDeviceRouteAllowed(method, path)
        internal fun extractOptionalPin(input: JSONObject): String? = LanAccessPolicy.extractOptionalPin(input)
        internal fun evaluateChildSwitch(
            targetId: String,
            activeId: String,
            isParent: Boolean,
            targetHasPin: Boolean,
            pin: String?,
            verifyChildPin: (String) -> Boolean,
            verifyParentPassword: (String) -> Boolean
        ): ChildSwitchDecision = LanAccessPolicy.evaluateChildSwitch(
            targetId, activeId, isParent, targetHasPin, pin, verifyChildPin, verifyParentPassword
        )
        internal fun checkApiAccess(
            method: String,
            path: String,
            deviceAccess: Boolean,
            parent: Boolean,
            integration: IntegrationAuth.Scope?
        ): Pair<Int, String>? = LanAccessPolicy.checkApiAccess(method, path, deviceAccess, parent, integration)
        internal fun formatSyncSuccessMessage(
            scopes: Set<String>,
            mode: String,
            syncedQuestions: Int,
            totalQuestions: Int
        ): String = LanAccessPolicy.formatSyncSuccessMessage(scopes, mode, syncedQuestions, totalQuestions)
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(if (BuildConfig.FLAVOR == "tv") 4 else 6)
    internal val router = Router()
    private val staticAssets = LanStaticAssets(context, assets)
    @Volatile private var running = false
    var boundPort: Int = port
        private set

    init { registerRoutes() }

    fun start() {
        if (running) return
        boundPort = tryBind(port) ?: tryBind(8090) ?: return
        running = true
        PeerDiscovery.start(context, store, portProvider = { boundPort }, ipProvider = { localIp() })
        Thread {
            while (running) {
                try {
                    val client = socket!!.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (running) Thread.sleep(50)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        PeerDiscovery.stop()
        try { socket?.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }

    private fun tryBind(p: Int): Int? = try {
        socket = ServerSocket(p)
        p
    } catch (e: Exception) {
        null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 8000
            try {
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (headers.size >= 100) return reply(c.getOutputStream(), 400, "text/plain", "Too many headers".toByteArray())
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
                }

                val out = c.getOutputStream()
                val origin = headers["origin"]
                if (method == "OPTIONS") return reply(out, 204, null, null, origin = origin)

                val contentLengthRaw = headers["content-length"]
                val contentLength = if (contentLengthRaw == null) 0L else contentLengthRaw.toLongOrNull() ?: -1L
                if (contentLength < 0L) return json(out, 400, err("bad_request").put("message", "Content-Length 格式错误"), origin)
                if (contentLength > MAX_PARENT_BODY) return json(out, 413, err("payload_too_large").put("message", "请求内容过大"), origin)

                val local = c.inetAddress?.isLoopbackAddress ?: false
                val deviceAccess = local && headers["x-device-token"] == AdminAuth.deviceToken
                val token = headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-admin-token"]

                val isPublicApi = path == "/api/hello" ||
                    (path == "/api/auth" && method == "POST") ||
                    (method == "GET" && path.startsWith(MediaStore.PREFIX))

                var parent = false
                var integration: IntegrationAuth.Scope? = null
                if (path.startsWith("/api/") && !isPublicApi) {
                    val adminKey = headers["x-admin-key"]
                    parent = AdminAuth.valid(token) || (adminKey != null && AdminAuth.verify(context, adminKey))
                    val integrationToken = token?.takeIf { it.startsWith("sotv_") }
                    integration = IntegrationAuth.verify(context, integrationToken)
                    if (integration != null && !deviceAccess && !parent && !IntegrationAuth.takeBudget()) {
                        return json(out, 429, err("too_many_requests").put("message", "请求太频繁，请稍后再试"), origin)
                    }
                    if (!deviceAccess && !parent && integration == null) {
                        return json(out, 401, err("unauthorized"), origin)
                    }
                }

                // 在真正分配缓冲区读请求体之前先按身份卡死体积上限，防止未登录连接用 24MB 请求打爆内存
                val maxBody = when {
                    !path.startsWith("/api/") || isPublicApi -> MAX_UNAUTH_BODY
                    parent -> MAX_PARENT_BODY
                    deviceAccess -> MAX_DEVICE_BODY
                    else -> MAX_INTEGRATION_BODY
                }
                if (contentLength > maxBody) {
                    return json(out, 413, err("payload_too_large").put("message", "请求内容过大"), origin)
                }

                val body = if (contentLength > 0L) readBody(input, contentLength.toInt()) else ""
                route(method, path, query, headers, body, out, local, deviceAccess, parent, token, integration)
            } catch (_: Exception) {
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: String,
        out: OutputStream,
        local: Boolean,
        deviceAccess: Boolean,
        parent: Boolean,
        token: String?,
        integration: IntegrationAuth.Scope?
    ) {
        val origin = headers["origin"]
        val q = try {
            if (query.isEmpty()) emptyMap() else query.split("&").associate {
                val kv = it.split("=", limit = 2)
                URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            return json(out, 400, err("bad_request").put("message", "请求参数格式错误"), origin)
        }

        if (path == "/api/auth" && method == "POST") {
            val input = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val remember = input.optBoolean("remember")
            val session = AdminAuth.login(context, input.optString("passphrase"), remember)
            return if (session == null) json(out, 401, err("管理密码错误，连续错误 5 次后需等待一分钟"), origin)
            else json(out, 200, JSONObject().put("token", session).put("expiresIn", if (remember) 30 * 86400 else 43200), origin)
        }

        checkApiAccess(method, path, deviceAccess, parent, integration)?.let { (code, message) ->
            val payload = if (code == 401) err("unauthorized")
            else if (message == "forbidden") err("forbidden")
            else err("forbidden").put("message", message)
            return json(out, code, payload, origin)
        }

        if (method != "GET") Diag.info("HTTP", "$method $path")
        val ctx = RouteContext(method, path, q, headers, body, out, local, deviceAccess, parent, token, integration)
        try {
            if (router.dispatch(ctx)) return
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (e is SocketException &&
                (msg.contains("Broken pipe", ignoreCase = true) || msg.contains("Connection reset", ignoreCase = true))
            ) {
                return
            }
            if (e is IllegalArgumentException) {
                Diag.warn("HTTP", "$method $path rejected: ${msg.take(160)}")
            } else {
                Diag.error("HTTP", "$method $path failed", e)
            }
            return json(out, 400, JSONObject().put("error", "bad_request").put("message", e.message ?: "请求格式错误"), origin)
        }

        if (method != "GET") return json(out, 405, err("method_not_allowed"), origin)
        staticAssets.serve(this, path, out, headers["accept-encoding"]?.contains("gzip") == true, headers["if-none-match"], local, origin)
    }

    /** 所有 /api 路由按领域分组登记；路径匹配顺序由 Router 保证（精确路径优先） */
    private fun registerRoutes() {
        registerDeviceRoutes()
        registerQuizRoutes()
        registerQuestionRoutes()
        registerSettingsRoutes()
        registerBackupRoutes()
    }

    internal fun json(ctx: RouteContext, code: Int, obj: JSONObject) =
        json(ctx.out, code, obj, ctx.headers["origin"])

    internal fun json(out: OutputStream, code: Int, obj: JSONObject, origin: String? = null) =
        reply(out, code, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8), origin = origin)

    internal fun err(code: String) = JSONObject().put("error", code)

    internal fun reply(
        out: OutputStream,
        code: Int,
        contentType: String?,
        bytes: ByteArray?,
        extra: Map<String, String> = emptyMap(),
        origin: String? = null
    ) {
        val body = bytes ?: ByteArray(0)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (!extra.containsKey("Cache-Control")) head.append("Cache-Control: no-store\r\n")
        val corsOrigin = allowedCorsOrigin(origin)
        extra.forEach { (k, v) ->
            if (k.equals("Vary", ignoreCase = true) && corsOrigin != null && corsOrigin != "*") {
                head.append("Vary: ").append(v).append(", Origin\r\n")
            } else {
                head.append(k).append(": ").append(v).append("\r\n")
            }
        }
        if (corsOrigin != null) {
            head.append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n")
            if (corsOrigin != "*" && extra.keys.none { it.equals("Vary", ignoreCase = true) }) {
                head.append("Vary: Origin\r\n")
            }
            head.append("Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\n")
            head.append("Access-Control-Allow-Headers: Content-Type,X-Admin-Token,Authorization,X-Admin-Key,X-Device-Token\r\n")
            head.append("Access-Control-Allow-Private-Network: true\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size, Charsets.UTF_8)
            }
            if (buf.size() >= 8192) throw IllegalArgumentException("Header line too long")
            buf.write(b)
        }
    }

    private fun readBody(input: BufferedInputStream, length: Int): String = readExactBody(input, length)

    fun localIp(): String? = try {
        val lanIp = try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            connectivity.allNetworks.asSequence().mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ||
                    !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) null
                else connectivity.getLinkProperties(network)?.linkAddresses?.asSequence()
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            }.firstOrNull()
        } catch (_: Exception) { null }
        lanIp ?: NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint &&
                !it.name.startsWith("tun") && !it.name.startsWith("tap") && !it.name.startsWith("wg") }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
