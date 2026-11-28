package com.studyontv.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** 尽早进入前台服务：Android 8+ 要求 startForegroundService 后 5 秒内必须调用 startForeground，绝不能放在数据库初始化后面（#166） */
internal fun GateService.enterForegroundNotification() {
    val channelId = "sotv_gate"
    if (Build.VERSION.SDK_INT >= 26 && !foregroundChannelCreated) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(NotificationChannel(channelId, "使用时间管理", NotificationManager.IMPORTANCE_LOW))
            foregroundChannelCreated = true
        }
    }
    val pi = runCatching {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }.getOrNull()
    val notification = runCatching {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        builder.setContentTitle("题了个题 · 使用时间管理中").setContentText("点击打开学习任务")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .apply { if (pi != null) setContentIntent(pi) }
            .setOngoing(true).build()
    }.getOrElse {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setOngoing(true).build()
    }
    try {
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
    } catch (e: Exception) {
        runCatching { Diag.warn("Service", "startForeground failed: ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }
}

/**
 * #17 次要项：悬浮窗 / 无障碍 / 使用情况访问全部缺失时，计时和拦截都不会生效。
 * 连续 CAPABILITY_GRACE_MS 没有任何监控能力才提示一次，权限恢复后清除通知。
 */
internal fun GateService.trackCapabilityImpl(monitoring: Boolean, now: Long) {
    if (monitoring) {
        capabilityMissingSince = -1L
        if (capabilityMissingNotified) {
            capabilityMissingNotified = false
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1006) } catch (_: Exception) { }
            Diag.info("Gate", "监控权限已恢复，清除「缺少权限」提示")
        }
        return
    }
    if (capabilityMissingSince < 0) { capabilityMissingSince = now; return }
    if (capabilityMissingNotified || now - capabilityMissingSince < GateService.CAPABILITY_GRACE_MS) return
    if (!Prefs.onboarded(this)) return
    capabilityMissingNotified = true
    Diag.warn("Gate", "no capability: 悬浮窗/无障碍/使用情况访问全部缺失 → 计时与拦截都不会生效，已提示家长")
    try {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sotv_gate_alert"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 6, Intent(this, OnboardingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        manager.notify(1006, builder.setContentTitle("使用时间管理还没生效")
            .setContentText("缺少悬浮窗权限，计时和答题拦截都不会生效，请点此开启")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
            .setAutoCancel(true).setOnlyAlertOnce(true).build())
    } catch (e: Exception) { Diag.error("Gate", "cannot notify missing capability", e) }
}

internal fun GateService.checkOverlayPermissionImpl() {
    val now = SystemClock.elapsedRealtime()
    if (lastOverlayCheckAt >= 0 && now - lastOverlayCheckAt < 60_000L) return
    lastOverlayCheckAt = now
    val supervisionEnabled = try { DeviceRuntime.store.config().optBoolean("supervisionEnabled", true) } catch (_: Exception) { true }
    val p = getSharedPreferences("sotv", Context.MODE_PRIVATE)
    val wasGranted = p.getBoolean("overlay_was_granted", false)
    val granted = GateOverlay.canDraw(this)
    if (granted && !wasGranted) {
        p.edit().putBoolean("overlay_was_granted", true).apply()
    }
    when (GatePresentation.overlayPermissionAlertAction(supervisionEnabled, wasGranted, granted)) {
        GatePresentation.OverlayAlertAction.CANCEL_ALERT -> {
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1003) } catch (_: Exception) { }
            return
        }
        GatePresentation.OverlayAlertAction.NONE -> return
        GatePresentation.OverlayAlertAction.NOTIFY_REVOKED -> {
            p.edit().putBoolean("overlay_was_granted", false).apply()
            Diag.warn("Gate", "overlay permission was revoked by system")
            BuglyInit.reportPermissionLost("SYSTEM_ALERT_WINDOW", "revoked by system firmware")
            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "sotv_gate_alert"
                if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                    NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(this, 2, Intent(this, OnboardingActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
                manager.notify(1003, builder.setContentTitle("悬浮窗权限缺失，答题拦截可能已停止")
                    .setContentText("孩子可能已绕过时间限制，请点此重新授权悬浮窗")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                    .setAutoCancel(true).setOnlyAlertOnce(true).build())
            } catch (e: Exception) { Diag.error("Gate", "cannot notify overlay revoked", e) }
        }
    }
}

internal fun GateService.checkAccessibilityLostImpl() {
    val now = SystemClock.elapsedRealtime()
    if (lastAccessibilityCheckAt >= 0 && now - lastAccessibilityCheckAt < 60_000L) return
    lastAccessibilityCheckAt = now
    val p = getSharedPreferences("sotv", Context.MODE_PRIVATE)
    val wasEnabled = p.getBoolean("accessibility_was_enabled", false)
    val nowEnabled = WatchAccessibilityService.enabled(this)
    val lost = !nowEnabled && (wasEnabled || p.getBoolean("accessibility_lost", false))
    p.edit().putBoolean("accessibility_was_enabled", nowEnabled).putBoolean("accessibility_lost", lost).apply()
    if (nowEnabled) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(1005)
    if (!wasEnabled || nowEnabled) return
    try {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sotv_gate_alert"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(this, 5, Intent(this, OnboardingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        manager.notify(1005, builder
            .setContentTitle("无障碍权限缺失，答题拦截可能已停止")
            .setContentText("孩子可能已绕过时间限制，请点此重新开启无障碍服务")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(false).build())
        val lostCount = Prefs.accessibilityLostCount(this) + 1
        Prefs.setAccessibilityLostCount(this, lostCount)
        Prefs.setAccessibilityLostAt(this, System.currentTimeMillis())
        Diag.warn("Gate", "accessibility permission lost, notified parent count=$lostCount")
        BuglyInit.reportPermissionLost("ACCESSIBILITY_SERVICE", "permission removed during monitoring or restart")
    } catch (e: Exception) { Diag.error("Gate", "cannot notify accessibility lost", e) }
}

internal fun GateService.notifyGateFallbackImpl(reason: String) {
    try {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sotv_gate_alert"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(this, GateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reason", reason)
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        // 失败原因的人话版进通知正文：家长能看到“为什么没弹”而不用去导诊断日志
        val why = GatePresentation.failureSummary(OverlayAccess.canDraw(this), WatchAccessibilityService.connected, Build.VERSION.SDK_INT)
        manager.notify(1002, builder.setContentTitle("到学习时间了")
            .setContentText("没自动弹出学习页（$why），点这里开始答题")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
            .setAutoCancel(true).setOnlyAlertOnce(gateFailures < 3).build())
        Diag.warn("Gate", "fallback notification posted reason=$reason why=$why failures=$gateFailures")
    } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot post fallback notification", e) }
}
