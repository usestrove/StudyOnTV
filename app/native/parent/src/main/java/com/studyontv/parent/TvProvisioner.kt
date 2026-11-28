package com.studyontv.parent

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 无线 ADB 一键装机 & 权限自动配置引擎。
 * 全程采用幂等、非破坏性命令：
 * - 无障碍列表与电视已有服务做集合并集（绝不覆盖/破坏 TalkBack 或电视语音助手）；
 * - 每项权限单独执行、逐项反馈（部分厂商 ROM 限制某项权限时不会中断其余关键授权）；
 * - 推送的安装包在 `finally` 中强制清理 `/data/local/tmp/studyontv-install.apk`。
 */
object TvProvisioner {
    const val PKG_TV = "com.studyontv.tv"
    const val PKG_MOBILE = "com.studyontv.mobile"
    const val MAIN_ACTIVITY_CLASS = "com.studyontv.tv.MainActivity"
    const val A11Y_SERVICE_CLASS = "com.studyontv.tv.WatchAccessibilityService"
    const val REMOTE_TMP_APK = "/data/local/tmp/studyontv-install.apk"

    enum class Mode {
        INSTALL_AND_CONFIGURE,
        FIX_PERMISSIONS_ONLY
    }

    enum class TargetFlavor(
        val label: String,
        val pkg: String,
        val pgyerKey: String,
        val manifestKey: String
    ) {
        TV("电视 / 盒子 / 投影仪版", PKG_TV, "5c78c116f305ded2cd7f34edb69a70ec", "tv"),
        MOBILE("手机 / 平板版", PKG_MOBILE, "9fa3ea546ca7601e842e3ead0be1679c", "mobile")
    }

    enum class StepState { RUNNING, OK, WARN, FAIL }

    data class StepUpdate(
        val id: String,
        val title: String,
        val state: StepState,
        val detail: String = ""
    )

    data class DeviceInfo(
        val brand: String,
        val model: String,
        val sdk: Int,
        val isTv: Boolean,
        val tvInstalled: Boolean,
        val mobileInstalled: Boolean
    ) {
        val displayName: String
            get() = listOf(brand, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "安卓设备" }
    }

    data class InstallParseResult(
        val success: Boolean,
        val code: String = "",
        val message: String = ""
    )

    data class ProvisionResult(
        val success: Boolean,
        val targetPackage: String,
        val deviceInfo: DeviceInfo?,
        val discoveredDevice: LanNet.DiscoveredDevice?,
        val okCount: Int,
        val warnCount: Int,
        val summary: String
    )

    interface Listener {
        fun onStep(update: StepUpdate)
        fun onWaitingForTvApproval(ip: String)
        fun onPushProgress(percent: Int)
    }

    private val SAFE_A11Y_REGEX = Regex("^[A-Za-z0-9._/$-]+$")

    /**
     * 非破坏性合并 `enabled_accessibility_services`：
     * - 清洗 `null` / 空串 / 多余冒号；
     * - 保留所有既有服务顺序；
     * - 兼容简写与全限定类名去重（如 `pkg/.WatchAccessibilityService` 与 `pkg/com.studyontv.tv.WatchAccessibilityService`）。
     */
    internal fun mergeAccessibilityServices(existingRaw: String?, targetComponent: String): String {
        val cleanedTarget = targetComponent.trim()
        require(cleanedTarget.isNotEmpty() && cleanedTarget.matches(SAFE_A11Y_REGEX)) {
            "无效无障碍组件名：$targetComponent"
        }
        val targetShort = toShortComponent(cleanedTarget)
        val result = ArrayList<String>()
        val seenCanonical = LinkedHashSet<String>()

        val raw = existingRaw.orEmpty().trim()
        if (raw.isNotEmpty() && !raw.equals("null", ignoreCase = true)) {
            for (token in raw.split(':')) {
                val item = token.trim()
                if (item.isEmpty() || item.equals("null", ignoreCase = true)) continue
                if (!item.matches(SAFE_A11Y_REGEX)) continue
                if (seenCanonical.add(canonicalComponentKey(item))) result.add(item)
            }
        }
        if (canonicalComponentKey(cleanedTarget) !in seenCanonical &&
            canonicalComponentKey(targetShort) !in seenCanonical
        ) {
            result.add(cleanedTarget)
        }
        return result.joinToString(":")
    }

    private fun toShortComponent(component: String): String {
        val slash = component.indexOf('/')
        if (slash <= 0) return component
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        return if (cls.startsWith("$pkg.")) "$pkg/${cls.removePrefix(pkg)}" else component
    }

    private fun canonicalComponentKey(component: String): String {
        val slash = component.indexOf('/')
        if (slash <= 0) return component
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        val fullCls = if (cls.startsWith(".")) "$pkg$cls" else cls
        return "$pkg/$fullCls"
    }

    internal fun detectIsTv(characteristics: String, featuresOutput: String): Boolean {
        val charsLower = characteristics.lowercase(Locale.ROOT)
        if (charsLower.split(',').any { it.trim() == "tv" }) return true
        val featLower = featuresOutput.lowercase(Locale.ROOT)
        return featLower.contains("android.software.leanback") ||
            featLower.contains("android.hardware.type.television")
    }

    internal fun parseInstallOutput(rawOutput: String): InstallParseResult {
        val trimmed = rawOutput.trim()
        if (trimmed.lines().any { it.trim().equals("Success", ignoreCase = true) }) {
            return InstallParseResult(true, "SUCCESS", "安装成功")
        }
        val code = Regex("Failure\\s*\\[([^\\]]+)\\]").find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val msg = when {
            code.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || code.contains("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES") ->
                "签名不一致：电视上已装有其他签名版本（如测试版），请先卸载旧版后再安装"
            code.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                "电视存储空间不足，请清理空间后重试"
            code.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "电视上已是更新的版本，无需降级安装（可切换为「仅一键修复权限」）"
            code.contains("INSTALL_FAILED_OLDER_SDK") ->
                "电视系统版本过低，不支持当前安装包"
            code.contains("INSTALL_FAILED_USER_RESTRICTED") || trimmed.contains("SecurityException", ignoreCase = true) ->
                "电视系统拦截了 ADB 静默安装：请检查电视开发者选项里的「允许通过 ADB 安装应用」开关，或改用 U 盘安装后再用本工具一键修权限"
            code.isNotEmpty() -> "安装失败（$code）"
            trimmed.isNotEmpty() -> "安装失败：${trimmed.lines().last().take(120)}"
            else -> "安装未返回结果，请检查电视屏幕是否弹出安装确认框"
        }
        return InstallParseResult(false, code, msg)
    }

    internal data class PermissionSpec(
        val id: String,
        val title: String,
        val command: String,
        val fallbackCommand: String? = null
    )

    internal fun buildPermissionPlan(pkg: String, sdk: Int, mergedA11y: String): List<PermissionSpec> {
        val list = ArrayList<PermissionSpec>()
        list.add(PermissionSpec("perm_overlay", "悬浮窗权限", "appops set $pkg SYSTEM_ALERT_WINDOW allow", if (sdk >= 24) "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow" else null))
        list.add(PermissionSpec("perm_usage", "使用情况权限", "appops set $pkg GET_USAGE_STATS allow", if (sdk >= 24) "cmd appops set $pkg GET_USAGE_STATS allow" else null))
        if (sdk >= 26) {
            list.add(PermissionSpec("perm_install", "在线升级权限", "appops set $pkg REQUEST_INSTALL_PACKAGES allow", "cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow"))
        }
        list.add(PermissionSpec("perm_a11y", "无障碍服务", "settings put secure enabled_accessibility_services '$mergedA11y' && settings put secure accessibility_enabled 1"))
        list.add(PermissionSpec("perm_secure", "无障碍自愈权限", "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"))
        if (sdk >= 33) {
            list.add(PermissionSpec("perm_notify", "通知权限", "pm grant $pkg android.permission.POST_NOTIFICATIONS"))
        }
        if (sdk >= 23) {
            list.add(PermissionSpec("perm_battery", "省电白名单", "dumpsys deviceidle whitelist +$pkg", if (sdk >= 24) "cmd deviceidle whitelist +$pkg" else null))
        }
        return list
    }

    internal fun isCommandOutputError(output: String): Boolean {
        val lower = output.lowercase(Locale.ROOT)
        return lower.contains("exception") ||
            lower.contains("securityexception") ||
            lower.contains("error:") ||
            lower.contains("unknown operation") ||
            lower.contains("not found") ||
            lower.contains("permission denied") ||
            lower.contains("failed")
    }

    /**
     * 执行完整的无线 ADB 装机 / 权限修复流程（必须在后台线程调用）。
     */
    fun provision(
        ip: String,
        port: Int = LanNet.DEFAULT_ADB_PORT,
        mode: Mode,
        apkFile: File?,
        preferredFlavor: TargetFlavor?,
        crypto: AdbCrypto,
        listener: Listener
    ): ProvisionResult {
        var deviceInfo: DeviceInfo? = null
        var okCount = 0
        var warnCount = 0

        emit(listener, "connect", "连接电视无线 ADB（$ip:$port）", StepState.RUNNING)
        val client = try {
            AdbClient.connect(
                host = ip,
                port = port,
                crypto = crypto,
                onWaitingForApproval = {
                    listener.onWaitingForTvApproval(ip)
                    emit(listener, "connect", "等待电视遥控器确认授权…", StepState.RUNNING, "请在电视弹窗上勾选「一律允许」并按确认（最长等待 60 秒）")
                }
            )
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            emit(listener, "connect", "连接电视无线 ADB", StepState.FAIL, msg)
            return ProvisionResult(false, "", null, null, 0, 0, msg)
        }

        var uploadedTmpApk = false
        try {
            emit(listener, "connect", "已连接电视无线 ADB（$ip:$port）", StepState.OK)

            // 1. 探测设备型号、SDK 与已安装形态
            emit(listener, "probe", "识别电视型号与系统版本", StepState.RUNNING)
            deviceInfo = probeDeviceInfo(client)
            val targetPkg = resolveTargetPackage(mode, preferredFlavor, deviceInfo)
            if (targetPkg == null) {
                val msg = "这台设备上尚未安装「题了个题」，请切换为「全新安装 / 升级并自动配置权限」模式"
                emit(listener, "probe", "未找到已安装的题了个题", StepState.FAIL, msg)
                return ProvisionResult(false, "", deviceInfo, null, 0, 0, msg)
            }
            val flavorLabel = if (targetPkg == PKG_TV) "电视版" else "手机/平板版"
            emit(listener, "probe", "已识别：${deviceInfo.displayName}（Android API ${deviceInfo.sdk}）", StepState.OK, "目标包名：$targetPkg（$flavorLabel）")

            // 2. 推送并安装 APK（安装/升级模式）
            if (mode == Mode.INSTALL_AND_CONFIGURE) {
                val localApk = apkFile ?: throw IOException("缺少待安装 APK 文件")
                val sizeMb = String.format(Locale.US, "%.1f MB", localApk.length() / (1024.0 * 1024.0))
                emit(listener, "push", "推送安装包到电视（$sizeMb）", StepState.RUNNING, "0%")
                client.pushFile(localApk, REMOTE_TMP_APK) { pct ->
                    listener.onPushProgress(pct)
                    emit(listener, "push", "推送安装包到电视（$sizeMb）", StepState.RUNNING, "$pct%")
                }
                uploadedTmpApk = true
                emit(listener, "push", "安装包推送完成（$sizeMb）", StepState.OK)

                emit(listener, "install", "静默安装到电视（可能需要 15~60 秒）", StepState.RUNNING)
                val parsed = parseInstallOutput(client.shell("pm install -r $REMOTE_TMP_APK", 150_000))
                if (!parsed.success) {
                    emit(listener, "install", "安装失败", StepState.FAIL, parsed.message)
                    return ProvisionResult(false, targetPkg, deviceInfo, null, okCount, warnCount, parsed.message)
                }
                emit(listener, "install", "静默安装成功", StepState.OK)
                okCount++
            }

            // 3. 读取现有无障碍服务并逐项授权
            val existingA11y = try { client.shell("settings get secure enabled_accessibility_services", 10_000) } catch (_: Exception) { "" }
            val mergedA11y = mergeAccessibilityServices(existingA11y, "$targetPkg/$A11Y_SERVICE_CLASS")
            for (spec in buildPermissionPlan(targetPkg, deviceInfo.sdk, mergedA11y)) {
                emit(listener, spec.id, spec.title, StepState.RUNNING)
                var out = safeShell(client, spec.command)
                if (isCommandOutputError(out) && spec.fallbackCommand != null) {
                    out = safeShell(client, spec.fallbackCommand)
                }
                if (isCommandOutputError(out)) {
                    warnCount++
                    emit(listener, spec.id, spec.title, StepState.WARN, "系统限制（可稍后在电视设置手动打开）：${out.lines().firstOrNull().orEmpty().take(90)}")
                } else {
                    okCount++
                    emit(listener, spec.id, spec.title, StepState.OK)
                }
            }

            // 4. 重启并启动应用（确保部分电视重新读取刚授予的 AppOps），再探测 /api/hello
            emit(listener, "launch", "重启「题了个题」并验证局域网管理服务", StepState.RUNNING)
            try { client.shell("am force-stop $targetPkg", 10_000) } catch (_: Exception) {}
            val startOut = safeShell(client, "am start -n $targetPkg/$MAIN_ACTIVITY_CLASS")
            var discovered: LanNet.DiscoveredDevice? = null
            for (attempt in 1..6) {
                try { Thread.sleep(if (attempt == 1) 1500L else 1200L) } catch (_: InterruptedException) { break }
                discovered = LanNet.probeHello(ip, LanNet.DEFAULT_HTTP_PORT, timeoutMs = 1500)
                if (discovered != null) break
            }
            if (discovered != null) {
                okCount++
                emit(listener, "launch", "已启动并连上家长管理后台（http://${discovered.host}/admin/）", StepState.OK, "设备名：${discovered.name} · v${discovered.version}")
            } else if (isCommandOutputError(startOut)) {
                warnCount++
                emit(listener, "launch", "请用遥控器在电视桌面手动打开一次「题了个题」", StepState.WARN, startOut.lines().firstOrNull().orEmpty().take(90))
            } else {
                emit(listener, "launch", "已发送启动指令（如首页仍在初始化，稍后回首页点扫描即可发现）", StepState.OK)
            }

            val summary = if (warnCount == 0) {
                "完成（$okCount 项成功）。建议关闭设备 ADB 调试。"
            } else {
                "完成（$okCount 项成功，$warnCount 项受系统限制）。建议关闭设备 ADB 调试。"
            }
            return ProvisionResult(true, targetPkg, deviceInfo, discovered, okCount, warnCount, summary)
        } catch (e: Exception) {
            val msg = e.message ?: "ADB 配置过程中断"
            emit(listener, "error", "流程中断", StepState.FAIL, msg)
            return ProvisionResult(false, "", deviceInfo, null, okCount, warnCount, msg)
        } finally {
            if (uploadedTmpApk) {
                try { client.shell("rm -f $REMOTE_TMP_APK", 8_000) } catch (_: Exception) {}
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun probeDeviceInfo(client: AdbClient): DeviceInfo {
        val brand = client.shell("getprop ro.product.brand", 10_000).trim()
        val model = client.shell("getprop ro.product.model", 10_000).trim()
        val sdk = client.shell("getprop ro.build.version.sdk", 10_000).trim().toIntOrNull() ?: 28
        val chars = client.shell("getprop ro.build.characteristics", 10_000).trim()
        val features = client.shell("pm list features", 15_000)
        val tvInstalled = client.shell("pm path $PKG_TV", 10_000).contains("package:")
        val mobileInstalled = client.shell("pm path $PKG_MOBILE", 10_000).contains("package:")
        return DeviceInfo(brand, model, sdk, detectIsTv(chars, features), tvInstalled, mobileInstalled)
    }

    private fun safeShell(client: AdbClient, command: String, timeoutMs: Int = 15_000): String =
        try { client.shell(command, timeoutMs).trim() } catch (e: Exception) { "Error: ${e.message}" }

    private fun emit(listener: Listener, id: String, title: String, state: StepState, detail: String = "") {
        listener.onStep(StepUpdate(id, title, state, detail))
    }

    internal fun resolveTargetPackage(
        mode: Mode,
        preferredFlavor: TargetFlavor?,
        info: DeviceInfo
    ): String? {
        if (mode == Mode.INSTALL_AND_CONFIGURE) {
            return preferredFlavor?.pkg ?: if (info.isTv) PKG_TV else PKG_MOBILE
        }
        // FIX_PERMISSIONS_ONLY：优先匹配设备上实际已安装的包
        if (preferredFlavor != null) {
            if (preferredFlavor == TargetFlavor.TV && info.tvInstalled) return PKG_TV
            if (preferredFlavor == TargetFlavor.MOBILE && info.mobileInstalled) return PKG_MOBILE
        }
        return when {
            info.isTv && info.tvInstalled -> PKG_TV
            !info.isTv && info.mobileInstalled -> PKG_MOBILE
            info.tvInstalled -> PKG_TV
            info.mobileInstalled -> PKG_MOBILE
            else -> null
        }
    }
}
