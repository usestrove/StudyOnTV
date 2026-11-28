package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

internal fun GateService.handleScreenBroadcast(intent: Intent?) {
    when (intent?.action) {
        Intent.ACTION_SCREEN_ON -> {
            screenOn = true
            shuttingDown = false
            powerExitUntil = 0L
        }
        Intent.ACTION_SCREEN_OFF -> {
            screenOn = false
            if (wasUsing) {
                val (_, awakeDelta) = sampleAwakeDelta()
                if (awakeDelta > 5_000L) Diag.warn("Gate", "tick delayed awake=${awakeDelta}ms (screen_off)")
                if (awakeDelta > 0L) {
                    try { DeviceRuntime.learning.consume(awakeDelta) } catch (_: Exception) { }
                }
                wasUsing = false
                beatWatchImpl(false)
            }
        }
        Intent.ACTION_SHUTDOWN, "android.intent.action.QUICKBOOT_POWEROFF" -> {
            shuttingDown = true
            screenOn = false
            wasUsing = false
            beatWatchImpl(false)
            handler.post { hideAllImpl(); countdown.hide() }
        }
    }
}

/**
 * 系统解析出来的 HOME 包：仅做 10 秒短缓存，避免用户换默认桌面或固件升级后失效（issue #66）。
 */
internal fun GateService.launcherPackage(): String? {
    val now = SystemClock.elapsedRealtime()
    if (launcherPkgAt >= 0 && now - launcherPkgAt < 10_000) return launcherPkgCache
    val home = try {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo
    } catch (_: Exception) { null }
    launcherPkgCache = home?.packageName
    launcherClassCache = home?.name
    launcherPkgAt = now
    return launcherPkgCache
}

/**
 * 「厂商把播放器塞进了 HOME 包」的情况：桌面本身不算在用，但桌面里在放影音时算（#99 / TCL）。
 */
internal fun GateService.launcherPlaying(pkg: String): Boolean {
    if (pkg != launcherPackage() && !Foreground.isLauncher(pkg)) return false
    val cls = Foreground.currentClass(pkg)
    if (cls != null && cls != launcherClassCache && !cls.contains("launcher", true) &&
        !cls.startsWith("android.") && !cls.startsWith("androidx.")) return true
    return Playback.hasActive(this)
}

/** 显示是否亮着；部分电视「关机」时 PowerManager 仍是 interactive，需要再确认一次 */
internal fun GateService.displayOn(): Boolean = try {
    val manager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    @Suppress("DEPRECATION")
    val state = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state
    // 拿不到显示状态时按亮着处理，避免误停计时
    state == null || state != android.view.Display.STATE_OFF
} catch (_: Exception) { true }

internal fun GateService.exempt(pkg: String, includeLauncher: Boolean = true): Boolean {
    if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" || pkg.contains("packageinstaller")) return true
    if (pkg.contains("permissioncontroller") || pkg.contains("inputmethod") || pkg.contains("keyboard")) return true
    if (SystemSettingsGuard.isSettings(this, pkg) || pkg in setOf("com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.emergency")) return true
    // 家长指定不计时的应用（屏保 / 网课）：不扣时长、不弹题，也不静音
    if (pkg in whitelistNow) return true
    if (!includeLauncher) return false
    val launcher = launcherPackage()
    return pkg == launcher || Foreground.isLauncher(pkg)
}

/** 学习状态可能未初始化（仅看设备能力时），诊断不能因此报错 */
private fun gateFreeState(): Boolean = try { DeviceRuntime.learning.status().optBoolean("free") } catch (_: Throwable) { false }

/** 家长面板里的能力自检；不依赖服务是否在运行 */
internal fun buildGateDiagnostics(context: Context, service: GateService?): JSONObject {
    val now = SystemClock.elapsedRealtime()
    val overlay = service?.overlayOrNull()
    val accessibilityGate = WatchAccessibilityService.presentedGate(now)
    val overlayPresenting = overlay?.presenting(now) == true
    val learningActivity = Foreground.learningActivity() != null
    val gateExpected = service?.gateExpectedNow == true
    val free = gateFreeState() && service?.settingsBlockedNow != true
    val mute = MediaPause.active
    val gateVisible = GatePresentation.gateVisible(accessibilityGate, overlayPresenting, learningActivity, gateExpected)
    val mismatch = GatePresentation.inconsistentMute(mute, gateVisible, InputSupport.active, free)
    val mismatchSince = AudioController.mismatchSince
    val lastMismatchAt = AudioController.lastMismatchAt
    val lastErrorAt = service?.lastGateErrorAt ?: -1L
    return JSONObject()
        .put("service", GateService.running)
        .put("homeBlockedPresses", WatchAccessibilityService.blockedHomePresses)
        .put("appSwitchBlockedPresses", WatchAccessibilityService.blockedAppSwitchPresses)
        .put("homePolicyExitAllowed", LearningNavigation.snapshot?.canExit ?: false)
        .put("homePolicyFresh", LearningNavigation.snapshot?.let { now - it.publishedAt <= 2000 } ?: false)
        .put("homeBlocking", LearningNavigation.shouldBlockHome())
        .put("homeFilterRequested", WatchAccessibilityService.keyFilterRequested())
        .put("muteWrites", MediaPause.writeCount)
        .put("overlay", OverlayAccess.canDraw(context))
        .put("usage", Foreground.hasUsageAccess(context))
        .put("accessibility", WatchAccessibilityService.connected)
        .put("accessibilityEnabled", WatchAccessibilityService.enabled(context))
        .put("accessibilityLost", !WatchAccessibilityService.enabled(context) && context.getSharedPreferences("sotv", Context.MODE_PRIVATE).getBoolean("accessibility_lost", false))
        .put("notificationListener", RevivalListener.enabled(context))
        .put("notificationListenerConnected", RevivalListener.connected)
        // === #120 红米电视：无障碍被系统清理连带关掉、进程被反复杀，给出可数的证据 ===
        .put("accessibilityLostCount", Prefs.accessibilityLostCount(context))
        .put("uncleanRestarts", ProcessHealth.uncleanRestarts(context))
        .put("gate", GateService.hasGate())
        .put("lastTickSeconds", if (service == null) -1 else ((SystemClock.elapsedRealtime() - service.lastTickElapsed) / 1000))
        .put("lastError", service?.lastGateError ?: "")
        // === #110 “静音了但弹不出来”的可读诊断（只新增，不删旧字段） ===
        .put("gateExpected", gateExpected)
        .put("gateVisible", gateVisible)
        .put("gateFailures", service?.gateFailures ?: 0)
        .put("lastGateErrorAt", lastErrorAt)
        .put("lastGateErrorAgeSeconds", if (lastErrorAt > 0) (now - lastErrorAt) / 1000 else -1)
        .put("overlayAttached", overlay?.isShowing ?: false)
        .put("overlayVisible", overlay?.verifiedVisible ?: false)
        .put("overlayPageReady", overlay?.pageReady ?: false)
        .put("overlayErrorKind", overlay?.lastErrorKind ?: "")
        .put("overlayWindowType", GateOverlay.windowTypeName(GateOverlay.applicationWindowType()))
        .put("overlayLastError", overlay?.lastError ?: "")
        .put("overlayProbeOk", OverlayAccess.probeSucceeded)
        .put("overlayProbeError", OverlayAccess.lastProbeError)
        .put("overlayProbeErrorKind", OverlayAccess.lastProbeErrorKind)
        .put("overlayProbeAgeSeconds", if (OverlayAccess.lastProbeAt < 0) -1 else (now - OverlayAccess.lastProbeAt) / 1000)
        // === #122 悬浮窗「存在但被视频层压住」的诊断 ===
        .put("overlayProbeOnly", OverlayAccess.probeOnly(context))
        // 回归测试里 DeviceRuntime 可能还没初始化，这里不能抛
        .put("forceFullscreenGate", try { DeviceRuntime.store.config().optBoolean("forceFullscreenGate", false) } catch (_: Exception) { false })
        .put("lastPauseHadAudio", MediaPause.lastPauseHadAudio)
        .put("fullscreenGateAgeSeconds", if (service == null || service.lastFullscreenAt <= 0) -1 else (now - service.lastFullscreenAt) / 1000)
        .put("foregroundPackage", service?.foregroundPackage ?: "")
        .put("foregroundClass", service?.foregroundPackage?.let { Foreground.currentClass(it) } ?: "")
        .put("foregroundIdentified", service?.foregroundPackage != null)
        .put("screenOn", service?.screenOn ?: true)
        .put("ownActivityForeground", service?.ownActivityForeground ?: false)
        .put("monitoringCapability", service?.monitoringNow ?: false)
        .put("mute", mute)
        .put("muteOwners", MediaPause.ownerCount)
        .put("muteStreamMuted", MediaPause.levelMuted)
        .put("muteForSeconds", if (!mute || MediaPause.lastPauseAt <= 0) 0L else (now - MediaPause.lastPauseAt) / 1000)
        .put("muteWithoutGate", mismatch)
        .put("muteWithoutGateSeconds", if (mismatch && mismatchSince > 0) (now - mismatchSince) / 1000 else -1)
        .put("lastMuteWithoutGateAgeSeconds", if (lastMismatchAt > 0) (now - lastMismatchAt) / 1000 else -1)
}
