package com.studyontv.tv

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View

class GateService : Service() {
    internal val handler = Handler(Looper.getMainLooper())
    internal var lastTickElapsed = SystemClock.elapsedRealtime()
    internal var lastTickUptime = SystemClock.uptimeMillis()
    @Volatile internal var wasUsing = false
    internal var lastGate = 0L
    internal var lastFallbackNotify = 0L
    internal var lastFallbackActivityAt = 0L
    /** 上一次主动改用全屏学习页的时间（#122），避免 1.8 秒防抖内重复 startActivity */
    @Volatile internal var lastFullscreenAt = 0L
    @Volatile internal var gateFailures = 0
    @Volatile internal var lastGateError = ""
    @Volatile internal var lastGateErrorAt = -1L
    /** 上一轮 tick 的状态：mediaTick / diagnostics 用它做「静音是否同源」的纯函数判定 */
    @Volatile internal var freeNow = false
    @Volatile internal var gateExpectedNow = false
    @Volatile internal var settingsBlockedNow = false
    internal var lastDiag = ""
    internal var lastAccessibilityCheckAt = -1L
    internal var lastOverlayCheckAt = -1L
    internal var lastTelemetryCheckAt = 0L
    internal var countdownActiveAt = -1L
    internal var countdownPackage: String? = null
    internal var lastAwake: Boolean? = null
    /** 「一个监控能力都没有」的起始时刻 / 是否已经提示过（#17：不能静默失效） */
    internal var capabilityMissingSince = -1L
    internal var capabilityMissingNotified = false
    @Volatile internal var foregroundPackage: String? = null
    @Volatile internal var ownActivityForeground = false
    @Volatile internal var monitoringNow = false
    /** 本轮 tick 读到的「不计时应用」白名单（屏保 / 网课 App） */
    internal var whitelistNow: Set<String> = emptySet()
    /** 观看计时心跳：上次写盘的开机时刻，以及上次写的是不是「正在计时」（#148） */
    internal var lastBeatAt = 0L
    internal var beatCounting = false
    /** 屏幕是否亮着：TCL 电视「关机未断电」时 PowerManager 仍报 interactive，须结合广播与显示状态 */
    @Volatile internal var screenOn = true
    /** 收到系统关机广播后禁止再拉起全屏学习页或覆盖层唤醒屏幕（ST-002 / GitHub #143） */
    @Volatile internal var shuttingDown = false
    /** 遥控器电源键退出全屏学习页后的退让截止时间（elapsedRealtime），避免下一 tick 立刻重拉 */
    @Volatile internal var powerExitUntil = 0L
    internal var screenReceiverRegistered = false
    internal var foregroundChannelCreated = false
    internal var launcherPkgCache: String? = null
    internal var launcherClassCache: String? = null
    internal var launcherPkgAt = -1L
    internal var anchor: View? = null
    // 字段名供 HomeNavigationUiRegression 反射读取，禁止改名
    private lateinit var overlay: GateOverlay
    internal val countdown by lazy { CountdownOverlay(this) }

    internal fun currentOverlay(): GateOverlay = overlay
    internal fun overlayOrNull(): GateOverlay? = if (::overlay.isInitialized) overlay else null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleScreenBroadcast(intent)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { checkForegroundImpl() } catch (e: Exception) { android.util.Log.e("StudyOnTV", "Screen time check failed", e) }
            handler.postDelayed(this, 1000)
        }
    }

    private val mediaTick = object : Runnable {
        override fun run() {
            runMediaTickImpl()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // #166：2GB 内存电视被强杀后重启时 IO 慢，先抢在任何数据库/磁盘操作前调用 startForeground，避免 5 秒超时崩掉
        enterForegroundNotification()
        DeviceRuntime.ensure(this)
        PeerDiscovery.start(this, DeviceRuntime.store)
        UninstallGuard.restoreOwnerPolicy(this)
        overlay = GateOverlay(this, GateOverlay.applicationWindowType())
        instance = this
        // 服务重建 = 新的一轮，静音/弹窗不同源的计时从零开始
        AudioController.reset()
        Prefs.setServiceRunning(this, true)
        // 屏幕亮灭与关机广播：用于识别 TCL「关机未断电」及创维等按电源键关机时抑制重拉（ST-002 / ST-112）
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction("android.intent.action.QUICKBOOT_POWEROFF")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (e: Exception) { Diag.warn("Gate", "cannot register screen receiver: ${e.message}") }
        MediaPause.recover(this)
        checkOverlayPermissionImpl()
        checkAccessibilityLostImpl()   // 进程被杀后重启，检查无障碍是否被系统清除
        Diag.info("Service", "GateService started via=$startVia")
        Watchdog.serviceStarted(this, startVia)
        Watchdog.schedule(this)
        Watchdog.scheduleAlarm(this)
        // 上一次进程是被固件直接杀掉的：先把死掉期间的观看时长补回来，再开始正常 tick
        recoverWatchGapImpl()
        handler.post(tick)
        handler.postDelayed(mediaTick, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 手机上从“最近任务”划掉应用时，部分系统会连带停止服务；2 秒后自动拉起 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart(this, 2000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { DeviceRuntime.learning.flushUsage() } catch (_: Exception) { }
        try { PeerDiscovery.stop() } catch (_: Exception) { }
        if (Prefs.onboarded(this)) scheduleRestart(this, 5000)
        handler.removeCallbacks(tick); handler.removeCallbacks(mediaTick)
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
            screenReceiverRegistered = false
        }
        if (::overlay.isInitialized) overlay.destroy()
        WatchAccessibilityService.hideGate(); countdown.destroy(); updateAnchorImpl(false)
        if (GatePresentation.shouldReleaseAllMuteOnDestroy(Foreground.learningActivity() != null)) {
            MediaPause.releaseAll(this)
        } else {
            MediaPause.release(this)
        }
        instance = null
        Prefs.setServiceRunning(this, false)
        // 仅在非观看态正常退出时清掉心跳；正在观看中被系统清理时保留心跳，供重启后补算
        if (!wasUsing) Prefs.clearWatchBeat(this)
        Diag.warn("Service", "GateService destroyed at uptime=${SystemClock.uptimeMillis() / 1000}s")
        super.onDestroy()
    }

    companion object {
        /** 一个监控能力都没有时，持续这么久才提示家长（避免刚开机 / 刚装完就弹） */
        internal const val CAPABILITY_GRACE_MS = 300_000L
        // 字段名供 HomeNavigationUiRegression 反射读取，禁止改名
        @Volatile private var instance: GateService? = null
        @Volatile private var startVia = "app"

        /** 孩子最近真正在用的受控应用：答题解锁后用它还原画面（#68 / #74） */
        @Volatile private var lastActivePackage: String? = null
        fun rememberActive(pkg: String) { lastActivePackage = pkg }
        fun rememberedActive(): String? = lastActivePackage
        val running: Boolean get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** 不抛异常的启动：后台启动前台服务在 Android 12+ 可能被拒绝（有悬浮窗 / 无障碍权限时通常允许） */
        fun ensureRunning(context: Context, via: String = "") {
            if (instance != null || !Prefs.onboarded(context)) return
            if (via.isNotEmpty()) startVia = via
            try { start(context) } catch (e: Exception) { android.util.Log.w("StudyOnTV", "GateService restart refused", e) }
        }

        fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val intent = Intent(context, GateService::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pending = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 7, intent, flags)
                    else PendingIntent.getService(context, 7, intent, flags)
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending)
            } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot schedule restart", e) }
        }

        fun hasGate(): Boolean = WatchAccessibilityService.hasGate() || instance?.overlay?.isShowing == true
        fun presentedOverlay(): Boolean = instance?.overlay?.presenting() == true
        fun showNavigationBlocked(): Boolean = instance?.overlay?.showNavigationBlocked() == true

        /** 家长面板里的能力自检；不依赖服务是否在运行 */
        fun diagnostics(context: Context): org.json.JSONObject = buildGateDiagnostics(context, instance)

        fun hideGates() {
            Handler(Looper.getMainLooper()).post { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
        }

        fun onOwnActivityResumed() {
            SystemSettingsGuard.revoke()
            Handler(Looper.getMainLooper()).post {
                if (InputSupport.active) { instance?.overlay?.suspend(); WatchAccessibilityService.suspendGate() }
                else { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
            }
        }

        /** 安装更新时放行系统安装确认页 2 分钟，避免闸门盖住安装按钮 */
        fun allowInstallPage() {
            instance?.let { service ->
                SystemSettingsGuard.grant()
                service.wasUsing = false
            }
            hideGates()
        }

        fun openingSystemPage(parentAuthorized: Boolean = false) {
            if (parentAuthorized) AdminAuth.markVerified()
            instance?.let { service ->
                val canIdentify = WatchAccessibilityService.connected || Foreground.hasUsageAccess(service)
                val status = DeviceRuntime.learning.status()
                val unlimited = status.optJSONObject("override")?.optString("mode") == "indefinite"
                val config = DeviceRuntime.store.config()
                val settingsLocked = config.optBoolean("supervisionEnabled", true) && !unlimited &&
                    config.optBoolean("blockSettingsDuringControl", true)
                if (!parentAuthorized && !AdminAuth.recentlyVerified() && canIdentify && settingsLocked) return
                SystemSettingsGuard.grant()
                service.wasUsing = false
            }
            hideGates()
        }

        /** 遥控器电源/休眠键退出全屏学习页时通知门卫进入短暂退让期（ST-002 / #143） */
        fun onPowerKeyExit(graceMs: Long = GatePresentation.POWER_EXIT_GRACE_MS) {
            instance?.let { service ->
                service.powerExitUntil = SystemClock.elapsedRealtime() + graceMs.coerceAtLeast(0L)
                service.wasUsing = false
            }
            hideGates()
        }

        /** 遥控操作后立即重新检查，不等下一秒；锁定时去掉 1.8 秒的防抖 */
        fun refreshNow() {
            instance?.handler?.post { instance?.lastGate = 0L; instance?.checkForegroundImpl() }
        }

        fun onForegroundChanged(context: Context, pkg: String?) {
            instance?.handler?.post { instance?.checkForegroundImpl() }
        }
    }
}
