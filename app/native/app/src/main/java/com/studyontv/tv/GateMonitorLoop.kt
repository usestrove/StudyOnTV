package com.studyontv.tv

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject

/** 计算自上次采样以来的真实清醒流逝（min(elapsed差, uptime差) 天然剔除深睡并保留 OEM 节流期间的观看时长） */
internal fun GateService.sampleAwakeDelta(): Pair<Long, Long> {
    val nowElapsed = SystemClock.elapsedRealtime()
    val nowUptime = SystemClock.uptimeMillis()
    val deltaElapsed = (nowElapsed - lastTickElapsed).coerceAtLeast(0L)
    val deltaUptime = (nowUptime - lastTickUptime).coerceAtLeast(0L)
    lastTickElapsed = nowElapsed
    lastTickUptime = nowUptime
    return nowElapsed to minOf(deltaElapsed, deltaUptime)
}

internal fun GateService.checkForegroundImpl() {
    val (now, awakeDelta) = sampleAwakeDelta()
    val config = DeviceRuntime.store.config()
    if (!config.optBoolean("supervisionEnabled", true)) {
        // 纯刷题背诵模式下不拦截其他应用、不锁系统设置、不静音，也不提醒缺少悬浮窗权限
        wasUsing = false
        freeNow = true
        gateExpectedNow = false
        settingsBlockedNow = false
        beatWatchImpl(false)
        updateAnchorImpl(false)
        hideAllImpl()
        countdown.hide()
        if (MediaPause.active) MediaPause.releaseAll(this)
        AudioController.syncLearningActivity(true, false)
        try { WatchAccessibilityService.setHomeFilter(false) } catch (_: Exception) { }
        trackCapabilityImpl(true, now)
        return
    }
    if (wasUsing && awakeDelta > 5_000L) {
        Diag.warn("Gate", "tick delayed awake=${awakeDelta}ms")
    }
    val status = if (wasUsing) DeviceRuntime.learning.consumeAndStatus(awakeDelta)
    else DeviceRuntime.learning.status()
    val canOverlay = OverlayAccess.canDraw(this)
    updateAnchorImpl(canOverlay && config.optBoolean("backgroundAnchorEnabled", true))
    if (!canOverlay) currentOverlay().hide()
    val free = status.optBoolean("free")
    val unlimited = status.optJSONObject("override")?.optString("mode") == "indefinite"
    if (InputSupport.active) {
        wasUsing = false
        beatWatchImpl(false)
        currentOverlay().suspend(); WatchAccessibilityService.suspendGate()
        countdown.hide()
        return
    }
    if (SystemSettingsGuard.active(now)) {
        settingsBlockedNow = false; freeNow = free; wasUsing = false; beatWatchImpl(false)
        hideAllImpl(); countdown.hide()
        if (MediaPause.active) releaseMuteWithoutGateImpl("settings")
        return
    }
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val interactive = power.isInteractive
    val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    val dispOn = displayOn()
    val awake = screenOn && dispOn && !shuttingDown && !locked &&
        (interactive || (DeviceBridge.isTv(this) && Playback.hasActive(this)))
    if (lastAwake != awake) { DeviceRuntime.learning.awake(awake); lastAwake = awake }
    val monitoring = canOverlay || WatchAccessibilityService.connected || Foreground.hasUsageAccess(this)
    // #17 次要项：新装机一个监控权限都没有时，不能一声不响（连续 5 分钟才提示一次）
    trackCapabilityImpl(monitoring, now)
    // 自家 Activity（学习页 / 家长设置 / 引导页）在前台时不计时也不拦截。
    val ownForeground = Foreground.hasResumedActivity()
    val pkg = Foreground.current(this)
    foregroundPackage = pkg
    ownActivityForeground = ownForeground
    monitoringNow = monitoring
    // 不计时白名单：系统屏保、网课 App 在前台时既不扣时长，也不弹题 / 静音。
    whitelistNow = WatchWhitelist.of(DeviceRuntime.store.config())
    val whitelistedNow = pkg != null && whitelistNow.contains(pkg)
    // 记住孩子正在用的应用，解锁后用它「回到原画面」（issue #68 / #74）
    if (pkg != null && pkg != packageName && !exempt(pkg, false)) GateService.rememberActive(pkg)
    // #93 / #17 / #66：识别不到前台应用按亮屏时间计时；桌面包放视频时由媒体/流量辅助兜底。
    val active = awake && monitoring && !ownForeground && !whitelistedNow && when {
        pkg == null -> true
        !exempt(pkg) -> true
        else -> launcherPlaying(pkg) || (pkg != packageName && (pkg == launcherPackage() || Foreground.isTransient(pkg)) &&
            DeviceRuntime.store.config().optBoolean("trafficAssistEnabled") && PlaybackTraffic.playing(this))
    }
    if (SystemSettingsGuard.tamperActive()) SystemSettingsGuard.syncForeground(this, pkg, now)
    val settingsBlocked = SystemSettingsGuard.blocked(
        context = this,
        awake = awake,
        monitoring = monitoring,
        unlimited = unlimited,
        pkg = pkg,
        blockSettings = config.optBoolean("blockSettingsDuringControl", true),
        recentlyVerified = AdminAuth.recentlyVerified(),
        now = now
    )
    settingsBlockedNow = settingsBlocked
    val effectiveFree = free && !settingsBlocked
    freeNow = effectiveFree
    if (effectiveFree && MediaPause.active) MediaPause.releaseAll(this)
    // 手机 / 平板的桌面本身不是内容页：到点后允许孩子回桌面挑白名单应用，但绝不能放行系统设置。
    val mobileHome = !settingsBlocked && pkg != null && !DeviceBridge.isTv(this) &&
        (pkg == launcherPackage() || Foreground.isLauncher(pkg)) && !launcherPlaying(pkg)
    val exemptNonSettings = !settingsBlocked && pkg != null && exempt(pkg, false)
    val protecting = awake && monitoring && !ownForeground && !whitelistedNow && !mobileHome && !exemptNonSettings
    val reason = status.getString("reason")
    val gateReason = if (settingsBlocked) "settings" else reason
    val pendingFeedback = DeviceRuntime.store.get("review_feedback").isNotEmpty()
    val practice = config.optString("playMode") == "practice"
    // 一次 tick 内统一口径：什么情况下应该有 Gate（静音与弹窗共用这个判断）
    gateExpectedNow = settingsBlocked || (!free && !whitelistedNow && !mobileHome && !exemptNonSettings && (gateReason.isNotEmpty() || pendingFeedback || practice))
    val feedbackVisible = !free && GateService.hasGate() && pendingFeedback
    AudioController.syncLearningActivity(effectiveFree, gateExpectedNow)
    wasUsing = active && !free && reason.isEmpty() && !feedbackVisible
    beatWatchImpl(wasUsing)
    if (!awake || !monitoring || mobileHome || (!settingsBlocked && (ownForeground || (reason.isEmpty() && !feedbackVisible) || exemptNonSettings))) hideAllImpl()
    // 冷启动校时后新一天的免费额度到账，收起先前为旧额度弹出的全屏页。
    if (awake && !gateExpectedNow && status.optBoolean("canExit") && status.optLong("freeQuotaSeconds") > 0) {
        val gateActivity = Foreground.learningActivity() as? GateActivity
        val launchReason = gateActivity?.intent?.getStringExtra("reason")
        if (!launchReason.isNullOrEmpty() && launchReason != "settings") gateActivity?.finish()
    }
    // #162：无障碍按键过滤只在学习页真的要拦 Home / 任务键时打开，避免影响厂商系统音量条
    try { WatchAccessibilityService.setHomeFilter(LearningNavigation.shouldBlockHome()) } catch (_: Exception) { }
    val snapshot = "reason=$gateReason free=$free awake=$awake screen=$screenOn inter=$interactive lock=$locked disp=$dispOn monitoring=$monitoring own=$ownForeground pkg=$pkg cls=${pkg?.let { Foreground.currentClass(it) }} active=$active gate=${GateService.hasGate()} overlay=$canOverlay mute=${MediaPause.active} failures=$gateFailures"
    if (snapshot != lastDiag) { lastDiag = snapshot; Diag.info("Gate", snapshot) }
    if (now - lastTelemetryCheckAt >= 1_800_000L) {
        lastTelemetryCheckAt = now
        Diag.uploadRuntimeLogsIfDue(this)
        Updater.check(this) { }
    }
    val state = GateTickState(now, free, awake, screenOn, monitoring, canOverlay, ownForeground, pkg,
        gateExpectedNow, gateReason, settingsBlocked, active, protecting)
    ensureGateVisibleImpl(state)
    // #110：静音必须由「真的弹出来了」背书——弹窗失败或被收起后立刻解除静音
    if (MediaPause.active && !muteBackedByVisibleGateImpl()) releaseMuteWithoutGateImpl("foreground")
    updateCountdownImpl(status, state)
}

/** 观看计时心跳（#148）：计时中每 5 秒同步落盘一次，停止时立即写 inactive。 */
internal fun GateService.beatWatchImpl(counting: Boolean) {
    val now = SystemClock.elapsedRealtime()
    if (!WatchGap.beatDue(lastBeatAt, now, counting, alreadyInactive = !beatCounting && lastBeatAt > 0)) return
    lastBeatAt = now
    beatCounting = counting
    Prefs.setWatchBeat(this, Prefs.WatchBeat(counting, System.currentTimeMillis(), now, SystemClock.uptimeMillis()))
}

/** 上次进程没走正常退出时，把被杀期间醒着的时间补扣回来（#148）。 */
internal fun GateService.recoverWatchGapImpl() {
    val beat = Prefs.watchBeat(this) ?: return
    Prefs.clearWatchBeat(this)
    lastBeatAt = 0L
    beatCounting = false
    if (!beat.active) return
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!power.isInteractive) return
    val gap = WatchGap.recoverMs(beat.elapsed, beat.uptime, SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
    if (gap <= 0L) return
    DeviceRuntime.learning.consumeRecovered(gap)
    Diag.warn("Gate", "进程被杀期间补算观看时长 ${gap / 1000} 秒（上次心跳 ${(System.currentTimeMillis() - beat.at) / 1000} 秒前）")
}

/** 确保 Gate 真的被弹出来（issue #110 / #122 / ST-002 / ST-101）。 */
internal fun GateService.ensureGateVisibleImpl(state: GateTickState) {
    val now = state.at
    val gateReason = state.gateReason
    val canOverlay = state.canOverlay
    val protecting = state.protecting
    val settingsBlocked = state.settingsBlocked
    val overlay = currentOverlay()
    // attach 了却一直不可见：当作失败并重试；连续失败达到阈值直接降级全屏 Activity
    if (overlay.isShowing && !overlay.presenting(now)) {
        lastGateError = overlay.lastError.ifEmpty { "overlay attached but not visible kind=${overlay.lastErrorKind}" }
        lastGateErrorAt = now
        gateFailures++
        Diag.warn("Gate", "overlay attached but never visible (attempt=$gateFailures) $lastGateError → hide and retry")
        // 用 hide 而不是 destroy：保留 WebView 供 60 秒内下次弹出复用
        overlay.hide()
        if (gateFailures >= 2 && (protecting || settingsBlocked) && gateReason.isNotEmpty()) {
            fallbackToActivityImpl(now, gateReason)
            return
        }
    }
    if (WatchAccessibilityService.hasGate() && !WatchAccessibilityService.presentedGate(now)) {
        lastGateError = WatchAccessibilityService.lastGateError()
        lastGateErrorAt = now
        gateFailures++
        Diag.warn("Gate", "accessibility gate attached but never visible (attempt=$gateFailures) $lastGateError → hide and retry")
        WatchAccessibilityService.hideGate()
        if (gateFailures >= 2 && (protecting || settingsBlocked) && gateReason.isNotEmpty()) {
            fallbackToActivityImpl(now, gateReason)
            return
        }
    }
    // 只有真正校验可见后才清零失败计数
    val gateVerifiedVisible = (overlay.isShowing && overlay.verifiedVisible) ||
        (WatchAccessibilityService.hasGate() && WatchAccessibilityService.presentedGate(now) &&
            now - lastGate >= GatePresentation.VISIBILITY_GRACE_MS)
    if (gateVerifiedVisible && gateFailures > 0) {
        Diag.info("Gate", "gate verified visible after $gateFailures failed attempts reason=$gateReason")
        gateFailures = 0
        lastGateError = ""
        lastGateErrorAt = -1L
    }
    if (Foreground.learningActivity() != null) {
        if (gateFailures > 0) Diag.info("Gate", "gate 已由全屏学习页展示，重置 $gateFailures 次失败记录")
        gateFailures = 0
        lastGateError = ""
        lastGateErrorAt = -1L
        return
    }
    // 关机态或遥控器电源键退让宽限期内禁止拉起全屏页或覆盖层（ST-002 / #143）
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!GatePresentation.fullscreenLaunchAllowed(power.isInteractive, screenOn, shuttingDown, now, powerExitUntil)) {
        return
    }
    val shouldShow = (protecting || settingsBlocked) && gateReason.isNotEmpty()
    val forcedFullscreen = DeviceRuntime.store.config().optBoolean("forceFullscreenGate", false)
    val preferFullscreen = GatePresentation.preferFullscreen(
        forcedFullscreen, OverlayAccess.probeOnly(this), MediaPause.lastPauseHadAudio)
    when (GatePresentation.fullscreenDecision(preferFullscreen, shouldShow, now, lastGate, lastFullscreenAt)) {
        GatePresentation.FullscreenDecision.WAIT_COOLDOWN -> return
        GatePresentation.FullscreenDecision.LAUNCH_FULLSCREEN -> {
            if (openFullscreenGateImpl(gateReason, forcedFullscreen)) {
                lastFullscreenAt = now
                lastGate = now
                hideAllImpl()
                return
            }
        }
        GatePresentation.FullscreenDecision.USE_OVERLAY -> Unit
    }
    if (!shouldShow || GateService.hasGate()) return
    val delay = if (gateFailures > 0) GatePresentation.failureRetryDelayMs(gateFailures) else GatePresentation.VISIBLE_RETRY_MS
    if (now - lastGate <= delay) return
    lastGate = now
    // SDK < 26 老盒子悬浮 WebView 易黑屏，优先走全屏 Activity；其余按无障碍覆盖层 → 悬浮 WebView
    val shown = try {
        if (Build.VERSION.SDK_INT < 26) false
        else if (canOverlay) WatchAccessibilityService.showGate(gateReason) || overlay.show(gateReason)
        else WatchAccessibilityService.showGate(gateReason)
    } catch (e: Exception) {
        lastGateError = "showGate ${e.javaClass.simpleName}: ${e.message ?: ""}"
        Diag.error("Gate", "gate show crashed", e)
        false
    }
    if (shown) return
    gateFailures++
    lastGateErrorAt = now
    lastGateError = GatePresentation.describeShowFailure(
        canDraw = canOverlay,
        accessibility = WatchAccessibilityService.connected,
        windowType = GateOverlay.applicationWindowType(),
        overlayError = overlay.lastError.ifEmpty { "kind=${overlay.lastErrorKind}" },
        api = Build.VERSION.SDK_INT)
    Diag.warn("Gate", "cannot show gate attempt=$gateFailures reason=$gateReason retry=${GatePresentation.failureRetryDelayMs(gateFailures)}ms $lastGateError")
    if (MediaPause.active) {
        Diag.warn("Audio", "gate not shown → release mute (owners=${MediaPause.ownerCount})")
        MediaPause.releaseAll(this)
    }
    fallbackToActivityImpl(now, gateReason)
}

/** 主动改用全屏学习页展示 Gate（#122 海信 VIDAA）。 */
internal fun GateService.openFullscreenGateImpl(gateReason: String, forced: Boolean): Boolean = try {
    val now = SystemClock.elapsedRealtime()
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!GatePresentation.fullscreenLaunchAllowed(power.isInteractive, screenOn, shuttingDown, now, powerExitUntil)) {
        Diag.info("Gate", "跳过全屏学习页拉起（关机或电源键退让期 interactive=${power.isInteractive} screenOn=$screenOn shuttingDown=$shuttingDown）")
        false
    } else {
        lastFallbackActivityAt = now
        startActivity(Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("reason", gateReason))
        Diag.info("Gate", if (forced) "家长已开启强制全屏学习页，跳过悬浮窗 reason=$gateReason"
        else "悬浮窗可能被视频层压住（探针权限+正在播放视频），改用全屏学习页 reason=$gateReason")
        true
    }
} catch (e: Exception) {
    lastGateError = "fullscreen startActivity=${e.javaClass.simpleName}: ${e.message ?: ""}"
    Diag.warn("Gate", "cannot open fullscreen gate: $lastGateError")
    false
}

/** 悬浮层 / 无障碍覆盖层都失败时的最后手段：全屏学习页 + 可点击通知 */
internal fun GateService.fallbackToActivityImpl(now: Long, gateReason: String) {
    if (Foreground.learningActivity() == null) {
        lastFallbackActivityAt = now
        try {
            startActivity(Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("reason", gateReason))
            Diag.info("Gate", "gate fallback activity started reason=$gateReason")
        } catch (e: Exception) {
            lastGateError += " startActivity=${e.javaClass.simpleName}: ${e.message ?: ""}"
            Diag.error("Gate", "cannot open gate activity", e)
        }
    }
    if (now - lastFallbackNotify > 10_000) {
        lastFallbackNotify = now
        notifyGateFallbackImpl(gateReason)
    }
}

internal fun GateService.gateShownImpl(): Boolean = GatePresentation.gateVisible(
    accessibilityGate = WatchAccessibilityService.presentedGate(),
    overlayPresenting = currentOverlay().presenting(),
    learningActivity = Foreground.learningActivity() != null,
    gateExpected = gateExpectedNow,
    fallbackPending = lastFallbackActivityAt > 0 && SystemClock.elapsedRealtime() - lastFallbackActivityAt < GatePresentation.FALLBACK_GRACE_MS)

internal fun GateService.muteBackedByVisibleGateImpl(): Boolean = AudioController.allowed(gateShownImpl(), freeNow)

internal fun GateService.releaseMuteWithoutGateImpl(source: String) =
    AudioController.releaseWithoutGate(this, source, gate = GateService.hasGate(), visible = currentOverlay().verifiedVisible,
        gateFailures = gateFailures, lastGateError = lastGateError)

/** 看电视期间在画面右上角显示剩余分钟数；不在看电视 / 拦截中 / 自由时段 / 家长解锁时隐藏 */
internal fun GateService.updateCountdownImpl(status: JSONObject, state: GateTickState) {
    val active = state.active
    val free = state.free
    val canOverlay = state.canOverlay
    val awake = state.awake
    val monitoring = state.monitoring
    val ownForeground = state.ownForeground
    val settingsBlocked = state.settingsBlocked
    val pkg = state.foregroundPackage
    val now = SystemClock.elapsedRealtime()
    if (active) { countdownActiveAt = now; countdownPackage = pkg }
    // 同一播放器短暂缓冲时保留角标，切换应用、关屏或进入管控页仍立即隐藏。
    val recentlyActive = countdownActiveAt >= 0 && now - countdownActiveAt < 12_000 && pkg == countdownPackage
    val seconds = status.optLong("remainingSeconds") + status.optLong("freeQuotaSeconds")
    val cfg = DeviceRuntime.store.config()
    val show = cfg.optBoolean("showCountdown", true) && canOverlay && awake &&
        monitoring && !ownForeground && !settingsBlocked && (pkg == null || !exempt(pkg, false)) &&
        (active || recentlyActive) && !free && status.optString("reason").isEmpty() &&
        status.optJSONObject("override") == null && seconds > 0 && !GateService.hasGate()
    if (show) countdown.update(seconds, cfg.optString("countdownPosition", "top-right")) else {
        countdown.hide()
        countdownActiveAt = -1L
        countdownPackage = null
    }
}

internal fun GateService.updateAnchorImpl(allowed: Boolean) {
    val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    if (!allowed) {
        anchor?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) { } }
        anchor = null
    } else if (anchor == null) {
        val view = View(this).apply { setBackgroundColor(Color.argb(1, 0, 0, 0)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val params = WindowManager.LayoutParams(1, 1, GateOverlay.applicationWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "StudyOnTV anchor"
        params.alpha = 0.01f
        try { manager.addView(view, params); anchor = view } catch (_: Exception) { }
    }
}

// 仅收起 Gate（悬浮层 / 无障碍覆盖层），角标由 updateCountdownImpl 单独管理以避免每秒闪烁
internal fun GateService.hideAllImpl() { currentOverlay().hide(); WatchAccessibilityService.hideGate() }

internal fun GateService.runMediaTickImpl() {
    try { checkOverlayPermissionImpl() } catch (e: Exception) { Diag.error("Gate", "overlay self-check failed", e) }
    try { checkAccessibilityLostImpl() } catch (e: Exception) { Diag.error("Gate", "accessibility self-check failed", e) }
    try { BankAutomation.tick(DeviceRuntime.store) } catch (_: Exception) { }
    // #110：只有 Gate 真的在屏幕上才持续静音；否则立即解除，不能让「静音了但没弹窗」卡住。
    if (MediaPause.active && !InputSupport.active) {
        if (muteBackedByVisibleGateImpl()) MediaPause.enforce(this)
        else releaseMuteWithoutGateImpl("media")
    } else if (!MediaPause.active) {
        // 上次恢复因权限 / 前台限制没生效时保留记录，这里每秒重试一次。
        MediaPause.recover(this)
    }
}
