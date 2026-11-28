package com.studyontv.tv

/**
 * 「静音」与「弹窗」是否同源的纯判定逻辑（issue #110）。
 *
 * 这里不碰任何窗口 / 音频 API，只做布尔运算，方便在 instrumentation 里直接验证；
 * GateService、GateOverlay 和 diagnostics() 共用同一份判断，避免两边口径不一致。
 */
object GatePresentation {
    /** 成功路径的防抖窗口：已经没有可见 Gate 时才需要重新检查 */
    const val VISIBLE_RETRY_MS = 1800L
    /** addView 成功但窗口还没完成一次可见性校验的宽限期（3 次校验：1.0s / 1.9s / 2.8s） */
    const val VISIBILITY_GRACE_MS = 3200L
    /** 兜底全屏 Activity 拉起后给它的启动宽限（避免刚 startActivity 就判定「没有 Gate」而解除静音） */
    const val FALLBACK_GRACE_MS = 5000L
    /** 主动切全屏学习页的最小重试冷却间隔 */
    const val FULLSCREEN_COOLDOWN_MS = 5_000L
    /** 按遥控器电源键退出全屏学习页后的关机/息屏退让宽限期（ST-002 / #143） */
    const val POWER_EXIT_GRACE_MS = 8_000L
    /** 连续失败后的重试间隔上限 */
    const val MAX_FAILURE_RETRY_MS = 10_000L

    /** 是否允许拉起全屏学习页或覆盖层：非交互、熄屏、关机中或处于电源键退让宽限期时一律禁止（ST-002 / #143） */
    fun fullscreenLaunchAllowed(
        interactive: Boolean,
        screenOn: Boolean,
        shuttingDown: Boolean,
        now: Long,
        powerExitUntil: Long
    ): Boolean = interactive && screenOn && !shuttingDown && (powerExitUntil <= 0L || now >= powerExitUntil)

    enum class FullscreenDecision {
        /** 不走全屏优先策略，继续走悬浮窗 / 无障碍覆盖层路径 */
        USE_OVERLAY,
        /** 处于全屏学习页冷却期内，等待全屏页拉起，禁止穿透到悬浮窗/无障碍层 */
        WAIT_COOLDOWN,
        /** 立即拉起全屏学习页（仅当拉起明确失败时才允许降级走悬浮窗路径） */
        LAUNCH_FULLSCREEN
    }

    /**
     * 全屏优先策略与冷却窗口的状态机判定（ST-101）：
     * - 当 `preferFullscreen && shouldShow` 时，冷却期内必须返回 [FullscreenDecision.WAIT_COOLDOWN]，
     *   严禁穿透到悬浮窗/无障碍覆盖层路径；
     * - 仅当冷却结束时返回 [FullscreenDecision.LAUNCH_FULLSCREEN]，且只有在拉起 Activity 明确失败时才允许降级。
     */
    fun fullscreenDecision(
        preferFullscreen: Boolean,
        shouldShow: Boolean,
        now: Long,
        lastGateAt: Long,
        lastFullscreenAt: Long
    ): FullscreenDecision {
        if (!preferFullscreen || !shouldShow) return FullscreenDecision.USE_OVERLAY
        val gateReady = lastGateAt <= 0L || now - lastGateAt > VISIBLE_RETRY_MS
        val fullscreenReady = lastFullscreenAt <= 0L || now - lastFullscreenAt > FULLSCREEN_COOLDOWN_MS
        return if (gateReady && fullscreenReady) FullscreenDecision.LAUNCH_FULLSCREEN
        else FullscreenDecision.WAIT_COOLDOWN
    }

    /** GateService 销毁时，若前台没有活跃的全屏学习页，必须释放全部静音持有者，避免残留静音（ST-103） */
    fun shouldReleaseAllMuteOnDestroy(hasLearningActivity: Boolean): Boolean = !hasLearningActivity

    enum class OverlayAlertAction {
        /** 首次未授权，无需发通知也不必取消 */
        NONE,
        /** 权限正常或总管控关闭，取消残留的 1003 告警通知 */
        CANCEL_ALERT,
        /** 权限曾授权但在管控开启期间被撤回，发出 1003 告警通知 */
        NOTIFY_REVOKED
    }

    /** 悬浮窗权限失联告警状态转移判定（ST-110） */
    fun overlayPermissionAlertAction(
        supervisionEnabled: Boolean,
        wasGranted: Boolean,
        nowGranted: Boolean
    ): OverlayAlertAction = when {
        !supervisionEnabled || nowGranted -> OverlayAlertAction.CANCEL_ALERT
        wasGranted -> OverlayAlertAction.NOTIFY_REVOKED
        else -> OverlayAlertAction.NONE
    }

    /**
     * 失败后的重试间隔：2s → 3s → 5s → 10s（上限）。
     * 失败路径不吃成功路径的 1.8s 防抖，也不会无限高频地重建 WebView。
     */
    fun failureRetryDelayMs(failures: Int): Long = when {
        failures <= 1 -> 2_000L
        failures <= 3 -> 3_000L
        failures <= 6 -> 5_000L
        else -> MAX_FAILURE_RETRY_MS
    }

    /**
     * #122：要不要跳过悬浮窗、直接用全屏学习页。
     *
     * 海信 VIDAA 会把普通应用的全屏悬浮窗压在硬件视频层下面：addView 成功、SurfaceFlinger 有 buffer、
     * `View.isShown` 也是 true，屏幕上却还是视频，任何自检都看不出来。两种情况改走 Activity：
     * 1. 家长显式开启「强制使用全屏学习页」；
     * 2. 悬浮窗权限只靠 addView 探针成立（固件 AppOps 不忠实），且暂停时确实有音频在播。
     */
    fun preferFullscreen(forced: Boolean, overlayProbeOnly: Boolean, hadAudioAtPause: Boolean): Boolean =
        forced || (overlayProbeOnly && hadAudioAtPause)

    /** 窗口 attach 了不等于可见：宽限期内算「正在弹出」，超出宽限期仍未校验通过就是失败 */
    fun presenting(attached: Boolean, verifiedVisible: Boolean, attachedForMs: Long): Boolean =
        attached && (verifiedVisible || attachedForMs < VISIBILITY_GRACE_MS)

    /** Gate 是否真的呈现在屏幕上（悬浮层 / 无障碍覆盖层 / 全屏学习页 / 刚拉起的兜底 Activity） */
    fun gateVisible(
        accessibilityGate: Boolean,
        overlayPresenting: Boolean,
        learningActivity: Boolean,
        gateExpected: Boolean,
        fallbackPending: Boolean = false
    ): Boolean = accessibilityGate || overlayPresenting || fallbackPending || (learningActivity && gateExpected)

    /** 静音是否允许：自由时段不允许；答题输入页或可见的 Gate 才算有背书 */
    fun muteAllowed(gateVisible: Boolean, inputActive: Boolean, free: Boolean): Boolean =
        !free && (inputActive || gateVisible)

    /** 「静音了但没 Gate」的不一致状态：诊断与修复都必须能看见它 */
    fun inconsistentMute(muteActive: Boolean, gateVisible: Boolean, inputActive: Boolean, free: Boolean): Boolean =
        muteActive && !muteAllowed(gateVisible, inputActive, free)

    /** 弹窗失败原因（技术细节，进 Diag / diagnostics） */
    fun describeShowFailure(canDraw: Boolean, accessibility: Boolean, windowType: Int, overlayError: String, api: Int): String {
        val window = try { GateOverlay.windowTypeName(windowType) } catch (_: Throwable) { "TYPE_$windowType" }
        val detail = overlayError.ifEmpty { "no-error-recorded" }
        return "window=$window api=$api canDraw=$canDraw accessibility=$accessibility overlayError=$detail"
    }

    /** 弹窗失败原因（人话，进家长能看到的兜底通知） */
    fun failureSummary(canDraw: Boolean, accessibility: Boolean, api: Int): String = when {
        !canDraw && !accessibility -> "缺少悬浮窗与无障碍权限"
        api < 26 -> "系统版本过低，无法悬浮显示"
        canDraw -> "悬浮窗被系统拒绝"
        else -> "无障碍覆盖层不可用"
    }
}
