package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BankAutomationAndMarketTest {

    @Test
    fun bankMarketAreAllPrivateRejectsEmptyAndMixedPublicRecords() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val lan = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        val pub = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

        assertFalse(BankMarket.areAllPrivate(emptyArray()))
        assertTrue(BankMarket.areAllPrivate(arrayOf(loopback)))
        assertTrue(BankMarket.areAllPrivate(arrayOf(loopback, lan)))
        assertFalse(BankMarket.areAllPrivate(arrayOf(loopback, pub)))
        assertFalse(BankMarket.areAllPrivate(arrayOf(pub)))
    }

    @Test
    fun updaterEffectiveCachedInfoSuppressesStaleCacheInLocalOnlyMode() {
        val stale = Updater.Info(
            latest = "9.9.9",
            current = "0.5.3",
            force = false,
            notes = "notes",
            url = "https://www.pgyer.com/app/installUpdate/abc",
            source = "蒲公英"
        )
        assertNull(Updater.effectiveCachedInfo(stale, localOnly = true))
        assertEquals(stale, Updater.effectiveCachedInfo(stale, localOnly = false))
    }

    @Test
    fun bankAutomationFormatFailureMessagePreservesRootCauseDetail() {
        val withDetail = BankAutomation.formatFailureMessage(
            "同步失败",
            "本地题库已保留",
            IllegalArgumentException("远程题库存在重复 ID 或题干")
        )
        assertTrue(withDetail.contains("远程题库存在重复 ID 或题干"))
        assertTrue(withDetail.contains("本地题库已保留"))

        val withoutDetail = BankAutomation.formatFailureMessage(
            "自动补题失败",
            "已有题目保留",
            RuntimeException()
        )
        assertEquals("自动补题失败，已有题目保留", withoutDetail)
    }

    @Test
    fun bankAutomationDetectsPermanentFailuresAndBacksOffTransientErrors() {
        assertTrue(BankAutomation.isPermanentAutoAiFailure(IllegalStateException("API Key 无效或没有权限，请检查密钥是否填写正确")))
        assertTrue(BankAutomation.isPermanentAutoAiFailure(IllegalStateException("账户余额不足，请先到服务商平台充值后再试")))
        assertTrue(BankAutomation.isPermanentAutoAiFailure(IllegalStateException("接口地址或模型名称不存在（HTTP 404）")))
        assertFalse(BankAutomation.isPermanentAutoAiFailure(IllegalStateException("连接 api.deepseek.com 超时")))
        assertFalse(BankAutomation.isPermanentAutoAiFailure(IllegalStateException("模型没有按要求返回 JSON：")))

        assertEquals(15 * 60_000L, BankAutomation.computeAutoAiBackoffMs(1))
        assertEquals(30 * 60_000L, BankAutomation.computeAutoAiBackoffMs(2))
        assertEquals(60 * 60_000L, BankAutomation.computeAutoAiBackoffMs(3))
        assertEquals(120 * 60_000L, BankAutomation.computeAutoAiBackoffMs(4))
        assertEquals(240 * 60_000L, BankAutomation.computeAutoAiBackoffMs(5))
        assertEquals(240 * 60_000L, BankAutomation.computeAutoAiBackoffMs(10))
    }

    @Test
    fun updateInstallReceiverClassifiesUserCancellationInsteadOfIncompatible() {
        val canceled = UpdateInstallReceiver.formatInstallFailure(
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            "INSTALL_FAILED_USER_RESTRICTED: Failed INSTALL_CANCELED_BY_USER"
        )
        assertTrue("Expected cancel prompt, got: $canceled", canceled.contains("取消") || canceled.contains("权限"))
        assertFalse("Must not falsely claim incompatible: $canceled", canceled.contains("不兼容"))

        val rejected = UpdateInstallReceiver.formatInstallFailure(
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,
            "INSTALL_FAILED_ABORTED: User rejected permissions"
        )
        assertTrue(rejected.contains("取消") || rejected.contains("权限"))

        val permDenied = UpdateInstallReceiver.formatInstallFailure(
            1,
            "INSTALL_FAILED_INTERNAL_ERROR: Permission Denied"
        )
        assertTrue(permDenied.contains("未知来源") || permDenied.contains("权限"))

        val realIncompat = UpdateInstallReceiver.formatInstallFailure(
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            "INSTALL_FAILED_NO_MATCHING_ABIS"
        )
        assertTrue(realIncompat.contains("不兼容"))
    }

    @Test
    fun webShellDetectsMissingWebViewCauseChain() {
        val leaf = RuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException: com.google.android.webview")
        val wrapped = RuntimeException("Unable to start activity", leaf)
        assertTrue(WebShell.isWebViewUnavailable(wrapped))
        assertTrue(WebShell.isWebViewUnavailable(UnsatisfiedLinkError("libwebviewchromium.so")))
        assertFalse(WebShell.isWebViewUnavailable(IllegalStateException("normal runtime error")))
    }

    @Test
    fun overlayAccessDeduplicatesRepeatedProbeWarningsUntilOutcomeChanges() {
        OverlayAccess.invalidate()
        assertTrue(OverlayAccess.shouldLogProbeOutcome("fail:bad-token:err"))
        assertFalse(OverlayAccess.shouldLogProbeOutcome("fail:bad-token:err"))
        assertTrue(OverlayAccess.shouldLogProbeOutcome("ok:true:TYPE_APPLICATION_OVERLAY"))
        assertFalse(OverlayAccess.shouldLogProbeOutcome("ok:true:TYPE_APPLICATION_OVERLAY"))
        OverlayAccess.invalidate()
        assertTrue(OverlayAccess.shouldLogProbeOutcome("ok:true:TYPE_APPLICATION_OVERLAY"))
    }
}
