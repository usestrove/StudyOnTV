package com.studyontv.tv

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设备状态、远程控制、密码/集成令牌、诊断日志与媒体文件路由。 */
internal fun LanServer.registerDeviceRoutes() {
    router.get("/api/hello") { ctx ->
        json(ctx, 200, JSONObject()
            .put("app", "StudyOnTV")
            .put("id", Prefs.deviceId(context))
            .put("version", BuildConfig.VERSION_NAME)
            .put("name", Prefs.deviceName(context))
            .put("ip", localIp() ?: "")
            .put("port", boundPort)
            .put("isTv", BuildConfig.FLAVOR == "tv")
            .put("questions", store.countQuestions())
            .put("needsToken", true)
            .put("passwordRequired", true))
    }
    // 本地媒体文件：文件名本身是 64 位 SHA-256，内容不可变，无需鉴权即可给 <img src="/api/media/..."> 直接加载并长期缓存
    router.prefixGet(MediaStore.PREFIX) { ctx ->
        val name = ctx.path.removePrefix(MediaStore.PREFIX)
        val mediaEtag = "\"$name\""
        if (MediaStore.isValidName(name) && ctx.headers["if-none-match"] == mediaEtag && File(store.mediaDir, name).isFile) {
            return@prefixGet reply(ctx.out, 304, null, null, mapOf("ETag" to mediaEtag, "Cache-Control" to "public, max-age=31536000, immutable"), ctx.headers["origin"])
        }
        val (bytes, mime) = MediaStore.readMedia(store.mediaDir, name)
            ?: return@prefixGet reply(ctx.out, 404, "text/plain; charset=utf-8", "404".toByteArray(), origin = ctx.headers["origin"])
        reply(
            ctx.out,
            200,
            mime,
            bytes,
            mapOf("Cache-Control" to "public, max-age=31536000, immutable", "ETag" to mediaEtag),
            ctx.headers["origin"]
        )
    }
    router.get("/api/apps") { ctx ->
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val items = pm.queryIntentActivities(main, 0).asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName || !seen.add(pkg)) return@mapNotNull null
                pkg to info.loadLabel(pm).toString().trim().take(60)
            }
            .sortedBy { it.second }
            .take(500)
            .map { (pkg, label) -> JSONObject().put("package", pkg).put("name", label.ifEmpty { pkg }) }
            .toList()
        json(ctx, 200, JSONObject().put("items", JSONArray(items)))
    }
    router.post("/api/system-settings") { ctx ->
        json(ctx, 200, JSONObject().put("ok", DeviceBridge.openSystemSettings(context)))
    }
    router.post("/api/password") { ctx ->
        val input = JSONObject(ctx.body)
        if (!AdminAuth.verify(context, input.optString("current"))) {
            return@post json(ctx, 403, err("wrong_password").put("message", "当前密码不正确，连续错误 5 次后需等待一分钟"))
        }
        AdminAuth.setPassword(context, input.optString("password"))
        val newToken = AdminAuth.issueSession(remember = false, context = context)
        json(ctx, 200, JSONObject().put("ok", true).put("token", newToken))
    }
    router.post("/api/logout") { ctx ->
        AdminAuth.forget(context, ctx.token)
        json(ctx, 200, JSONObject().put("ok", true))
    }
    router.post("/api/remote") { ctx ->
        val parent = ctx.parent
        val control = ctx.integration?.control == true
        if (!parent && !control) return@post json(ctx, 403, err("forbidden").put("message", "请先用管理密码登录"))
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        val action = input.optString("action")
        require(!input.has("untilShutdown") || input.get("untilShutdown") is Boolean) { "untilShutdown 需要为布尔值" }
        val untilShutdown = input.optBoolean("untilShutdown")
        require(!untilShutdown || action == "unlock") { "只有临时解锁支持到本次关机" }
        if (untilShutdown && input.has("minutes")) {
            require(input.get("minutes") is Number && input.getDouble("minutes") == 0.0) {
                "到本次关机的分钟数必须为 0 或省略"
            }
        }
        if (action == "clear_extra") {
            val res = DeviceRuntime.learning.clearExtraPool()
            store.logEvent("remote", JSONObject().put("action", action).put("cleared", res.optLong("cleared", 0L)).put("source", if (parent) "panel" else "integration"))
            GateService.refreshNow()
            return@post json(ctx, 200, JSONObject().put("learning", res.getJSONObject("learning")).put("cleared", res.getLong("cleared")))
        }
        if (action == "credit") {
            val value = input.getDouble("minutes")
            require(value.isFinite() && value == value.toInt().toDouble()) { "调整时长需要整数分钟" }
        }
        val status = DeviceRuntime.learning.remote(action, input.optInt("minutes"),
            if (parent) "panel" else "integration", untilShutdown)
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("learning", status))
    }
    router.get("/api/integration") { ctx -> json(ctx, 200, IntegrationAuth.state(context)) }
    router.post("/api/integration") { ctx ->
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        val read = input.optBoolean("read", true)
        val control = input.optBoolean("control", false)
        if (!read && !control) return@post json(ctx, 200, err("bad_scope").put("message", "至少选择只读或控制中的一种权限"))
        val token = IntegrationAuth.issue(context, read, control)
        json(ctx, 200, JSONObject().put("token", token).put("integration", IntegrationAuth.state(context)))
    }
    router.put("/api/integration") { ctx ->
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        IntegrationAuth.update(context,
            if (input.has("enabled")) input.optBoolean("enabled") else null,
            if (input.has("read")) input.optBoolean("read") else null,
            if (input.has("control")) input.optBoolean("control") else null)
        json(ctx, 200, IntegrationAuth.state(context))
    }
    router.delete("/api/integration") { ctx ->
        IntegrationAuth.revoke(context)
        json(ctx, 200, IntegrationAuth.state(context))
    }
    router.get("/api/logs") { ctx ->
        json(ctx, 200, JSONObject().put("lines", Diag.read(context, ctx.query["limit"]?.toIntOrNull()?.coerceIn(50, 3000) ?: 800, ctx.query["q"] ?: "")))
    }
    router.delete("/api/logs") { ctx ->
        Diag.clear(context)
        json(ctx, 200, JSONObject().put("ok", true))
    }
    router.get("/api/logs/export") { ctx ->
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        reply(ctx.out, 200, "application/zip", Diag.export(context), mapOf("Content-Disposition" to "attachment; filename=\"studyontv-logs-$stamp.zip\""), ctx.headers["origin"])
    }
    router.get("/api/device") { ctx ->
        val clock = store.clockReading()
        json(ctx, 200, JSONObject().put("name", Prefs.deviceName(context))
            .put("version", BuildConfig.VERSION_NAME).put("flavor", BuildConfig.FLAVOR).put("localOnly", LocalOnlyGuard.enabled).put("ip", localIp() ?: "").put("port", boundPort)
            .put("uninstallProtected", UninstallGuard.active(context)).put("deviceOwner", UninstallGuard.owner(context))
            .put("launcherHidden", LauncherIcon.hidden(context)).put("desktop", DesktopMode.state(context)).put("autostart", Watchdog.report(context)).put("monitor", GateService.diagnostics(context))
            // 家长在别的时区时，面板要能看出「设备现在几点」到底按哪个时区算
            .put("timezone", DeviceClock.describe(store, clock.millis))
            .put("deviceTime", DeviceClock.calendar(store, clock.millis).time.toString())
            .put("clockSource", clock.source.id)
            .put("keepalive", KeepAlive.report(context)).put("buglyConsent", !LocalOnlyGuard.enabled && Prefs.buglyConsent(context))
            .put("crashUpload", crashUploadState(context)))
    }
    router.put("/api/device/crash") { ctx ->
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        if (input.has("consent")) Prefs.setCrashUploadConsent(context, input.optBoolean("consent"))
        if (input.has("endpoint")) {
            val endpoint = input.optString("endpoint").trim()
            require(endpoint.isEmpty() || endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "上传地址需要以 http:// 或 https:// 开头" }
            Prefs.setCrashEndpoint(context, endpoint)
        }
        if (input.has("token")) Prefs.setCrashToken(context, input.optString("token").trim())
        json(ctx, 200, JSONObject().put("ok", true).put("crashUpload", crashUploadState(context)))
    }
    router.put("/api/device/bugly") { ctx ->
        if (LocalOnlyGuard.enabled) {
            BuglyInit.revoke(context)
            json(ctx, 200, JSONObject().put("ok", true).put("buglyConsent", false).put("localOnly", true))
        } else {
            val enabled = JSONObject(ctx.body).optBoolean("enabled")
            if (enabled) BuglyInit.initWithConsent(context) else BuglyInit.revoke(context)
            json(ctx, 200, JSONObject().put("ok", true).put("buglyConsent", Prefs.buglyConsent(context)))
        }
    }
    router.delete("/api/device/uninstall-guard") { ctx ->
        try {
            UninstallGuard.setEnabled(context, false)
            json(ctx, 200, JSONObject().put("ok", true).put("uninstallProtected", false))
        } catch (e: Exception) {
            json(ctx, 500, err(e.message ?: "failed"))
        }
    }
    router.put("/api/device/uninstall-guard") { ctx ->
        UninstallGuard.setEnabled(context, true)
        json(ctx, 200, JSONObject().put("ok", true).put("uninstallProtected", UninstallGuard.active(context)))
    }
    router.put("/api/device/desktop") { ctx ->
        val enabled = JSONObject(ctx.body).optBoolean("enabled")
        try {
            json(ctx, 200, DesktopMode.setEnabled(context, enabled))
        } catch (e: Exception) {
            json(ctx, 500, err(e.message ?: "切换电视桌面失败"))
        }
    }
    router.post("/api/device/desktop/choose") { ctx ->
        DesktopMode.openChooser(context)
        json(ctx, 200, DesktopMode.state(context))
    }
    router.put("/api/device/launcher") { ctx ->
        val hidden = LauncherIcon.setHidden(context, JSONObject(ctx.body).optBoolean("hidden"))
        json(ctx, 200, JSONObject().put("ok", true).put("launcherHidden", hidden))
    }
    router.put("/api/device") { ctx ->
        val name = JSONObject(ctx.body).optString("name").trim()
        require(name.length in 1..32) { "设备名称需要 1–32 个字" }
        Prefs.setDeviceName(context, name)
        json(ctx, 200, JSONObject().put("ok", true).put("name", name))
    }
}

private fun crashUploadState(context: Context) = JSONObject()
    .put("consent", Prefs.crashUploadConsent(context))
    .put("endpoint", Prefs.crashEndpointStored(context))
    .put("builtInEndpoint", BuildConfig.CRASH_ENDPOINT)
    .put("hasToken", Prefs.crashToken(context).isNotBlank())
    .put("builtInToken", BuildConfig.CRASH_TOKEN.isNotBlank())
    .put("pending", CrashDetector.hasPending(context))
