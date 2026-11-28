package com.studyontv.tv

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 悬浮窗可用性判定（issue #92 长虹：悬浮窗已开仍提示未开启）。
 *
 * `Settings.canDrawOverlays()` 只读 AppOps 的 SYSTEM_ALERT_WINDOW，部分厂商固件把开关映射到
 * 别的 op / 另一个 uid（多用户、分身），于是系统真实允许、我们却永远读到 false；而旧代码在
 * 真正 `addView` 之前就放弃了，「打开权限也不起作用」。
 *
 * 这里在不申请新权限的前提下按三级判定：设置 API → AppOps → 一次真实 `addView` 探针。
 * 探针结果（成功 / 失败原因）在本次会话内缓存，失败后 60 秒内不重复探测（不是每秒执行）。
 */
object OverlayAccess {
    /** 探针失败后的重试间隔：不能每秒都尝试添加窗口 */
    const val RETRY_MS = 60_000L
    /** 探测成功后的复检间隔：厂商允许 addView 时也不能每分钟都建一次窗口 */
    const val SUCCESS_RECHECK_MS = 5 * 60_000L

    @Volatile private var probed = false
    @Volatile private var probeOk = false
    @Volatile var lastProbeAt: Long = -1L
        private set
    /** 探针失败原因的原始描述：含异常类名与窗口类型，便于按机型排查 */
    @Volatile var lastProbeError: String = ""
        private set
    /** 分类：permission / bad-token / invalid-display / illegal-argument / window / no-window-service */
    @Volatile var lastProbeErrorKind: String = ""
        private set
    /** 最近一次已落盘的探针日志指纹：状态未变化时不重复刷屏，避免挤爆 sotv.log */
    @Volatile private var lastLoggedProbeKey: String = ""
    /** 最近一次探针是否成功（Settings=false 但厂商实际允许时为 true） */
    val probeSucceeded: Boolean get() = probeOk && probed

    /** 用户从系统设置返回后调用：让下一次 canDraw() 重新做一次真实探测 */
    fun invalidate() {
        probed = false
        lastLoggedProbeKey = ""
    }

    internal fun shouldLogProbeOutcome(key: String): Boolean {
        if (key == lastLoggedProbeKey) return false
        lastLoggedProbeKey = key
        return true
    }

    fun canDraw(context: Context): Boolean = settingsCanDraw(context) || appOpsAllows(context) || probe(context)

    /**
     * 权限只靠 addView 探针成立：Settings 与 AppOps 都说没有。
     *
     * 海信 VIDAA（#122）就是这种机型：探针能加窗，但普通悬浮窗被硬件视频层压住，
     * 画面实际仍是视频；这类设备在播放时改走全屏学习页更可靠。
     */
    fun probeOnly(context: Context): Boolean =
        !settingsCanDraw(context) && !appOpsAllows(context) && probe(context)

    /** 旧的唯一真值：Settings.canDrawOverlays（厂商不忠实时会一直是 false） */
    @Suppress("DEPRECATION")
    fun settingsCanDraw(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            context.checkCallingOrSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) { false }

    private fun appOpsAllows(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), context.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** 真实探测：能成功 addView 一个 1×1 透明窗口就说明系统真的允许（窗口会被立即移除） */
    private fun probe(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        val interval = if (probeOk) SUCCESS_RECHECK_MS else RETRY_MS
        if (probed && now - lastProbeAt < interval) return probeOk
        probed = true
        lastProbeAt = now
        if (Looper.myLooper() == Looper.getMainLooper()) return probeOnMain(context)
        // WindowManager.addView 需要当前线程有 Looper，非主线程调用时跳到主线程执行
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        val posted = try {
            Handler(Looper.getMainLooper()).post { result = probeOnMain(context); latch.countDown() }
        } catch (_: Throwable) { false }
        if (!posted) return false.also { record("no-main-looper", "cannot post probe to main thread") }
        return if (latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) result else false.also {
            record("window", "probe timed out after 3000ms")
        }
    }

    private fun probeOnMain(context: Context): Boolean {
        val type = GateOverlay.applicationWindowType()
        val name = GateOverlay.windowTypeName(type)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) return false.also { record("no-window-service", "WindowManager unavailable type=$name") }
        val view = View(context)
        val params = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "StudyOnTV overlay probe"
        params.alpha = 0.01f
        return try {
            manager.addView(view, params)
            var removed = true
            try { manager.removeViewImmediate(view) } catch (_: Throwable) { removed = false }
            probeOk = true
            lastProbeErrorKind = ""
            lastProbeError = ""
            if (shouldLogProbeOutcome("ok:$removed:$name")) {
                Diag.warn("Gate", "Settings.canDrawOverlays=false 但 1×1 探针 addView 成功（removed=$removed type=$name）→ 厂商 AppOps 不一致，本次会话按有权限处理")
            }
            true
        } catch (e: Throwable) {
            probeOk = false
            record(classify(e), "${e.javaClass.name}: ${e.message ?: ""} type=$name")
            // 探测失败必须清理干净，不能留下一个 1×1 窗口
            try { manager.removeViewImmediate(view) } catch (_: Throwable) { }
            false
        }
    }

    private fun record(kind: String, detail: String) {
        lastProbeErrorKind = kind
        lastProbeError = detail.ifEmpty { kind }
        // 按异常类型与窗口类型去重（忽略 ViewRootImpl$W@xxxx 实例地址变化），状态变化时才落盘
        val normalized = lastProbeError.replace(Regex("@[0-9a-fA-F]+"), "@_")
        if (shouldLogProbeOutcome("fail:$kind:$normalized")) {
            Diag.warn("Gate", "overlay probe failed kind=$kind $lastProbeError")
        }
    }

    private fun classify(e: Throwable): String = when (e) {
        is WindowManager.BadTokenException -> "bad-token"
        is WindowManager.InvalidDisplayException -> "invalid-display"
        is SecurityException -> "permission"
        is IllegalArgumentException -> "illegal-argument"
        else -> "window"
    }
}
