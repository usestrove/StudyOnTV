package com.studyontv.tv

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerTest {

    @Test
    fun validateDownloadedSizeDetectsEmptyAndTruncatedDownloads() {
        assertNotNull(ApkInstaller.validateDownloadedSize(done = 0L, contentLength = -1L))
        assertNotNull(ApkInstaller.validateDownloadedSize(done = 0L, contentLength = 1000L))
        val truncated = ApkInstaller.validateDownloadedSize(done = 500L, contentLength = 1000L)
        assertNotNull(truncated)
        assertTrue(truncated!!.contains("下载不完整"))

        assertNull(ApkInstaller.validateDownloadedSize(done = 1000L, contentLength = 1000L))
        assertNull(ApkInstaller.validateDownloadedSize(done = 2048L, contentLength = -1L))
    }

    @Test
    fun checkLocalOnlyBlockedGuardsInstallAndDownload() {
        val blocked = ApkInstaller.checkLocalOnlyBlocked(localOnly = true)
        assertNotNull(blocked)
        assertTrue(blocked!!.contains("EU Local-Only"))
        assertNull(ApkInstaller.checkLocalOnlyBlocked(localOnly = false))
    }

    @Test
    fun signatureVerdictAcceptsMatchingCertsAndRejectsDifferentOnes() {
        assertNull(ApkInstaller.signatureVerdict(setOf("aa"), setOf("aa")))
        // 证书轮换：历史证书之一命中即可
        assertNull(ApkInstaller.signatureVerdict(setOf("new", "old"), setOf("old")))

        val rejected = ApkInstaller.signatureVerdict(setOf("aa"), setOf("bb"))
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("签名"))
    }

    @Test
    fun signatureVerdictDefersToSystemInstallerWhenCertsAreUnreadable() {
        // 部分电视 ROM 对 APK 归档读不到证书；此时不能提前拒绝，交给系统安装器做最终校验
        assertNull(ApkInstaller.signatureVerdict(setOf("aa"), emptySet()))
        assertNull(ApkInstaller.signatureVerdict(emptySet(), setOf("aa")))
        assertNull(ApkInstaller.signatureVerdict(emptySet(), emptySet()))
    }

    @Test
    fun confirmProbeResultUsesSessionOnlyWhenConfirmPageExists() {
        assertFalse(ApkInstaller.confirmProbeResult { false })
        assertTrue(ApkInstaller.confirmProbeResult { true })
        assertFalse(ApkInstaller.confirmProbeResult { throw SecurityException("probe blocked") })
    }

    @Test
    fun packageMismatchErrorExplainsDebugVsReleaseAndCrossFlavor() {
        assertNull(ApkInstaller.packageMismatchError("com.studyontv.mobile", "com.studyontv.mobile"))
        val debugToRelease = ApkInstaller.packageMismatchError("com.studyontv.mobile.debug", "com.studyontv.mobile")
        assertNotNull(debugToRelease)
        assertTrue(debugToRelease!!.contains("开发调试版"))
        assertTrue(debugToRelease.contains("正式版"))

        val crossFlavor = ApkInstaller.packageMismatchError("com.studyontv.mobile", "com.studyontv.tv")
        assertNotNull(crossFlavor)
        assertTrue(crossFlavor!!.contains("com.studyontv.mobile"))
        assertTrue(crossFlavor.contains("com.studyontv.tv"))
    }

    @Test
    fun countdownOverlayPositionNormalizationAndGravityMapping() {
        assertEquals("top-right", CountdownOverlay.normalizePosition(""))
        assertEquals("top-right", CountdownOverlay.normalizePosition("center"))
        assertEquals("top-left", CountdownOverlay.normalizePosition("top-left"))
        assertEquals("bottom-right", CountdownOverlay.normalizePosition("bottom-right"))
        assertEquals("bottom-left", CountdownOverlay.normalizePosition("bottom-left"))

        assertEquals(Gravity.TOP or Gravity.END, CountdownOverlay.resolveGravity("top-right"))
        assertEquals(Gravity.TOP or Gravity.START, CountdownOverlay.resolveGravity("top-left"))
        assertEquals(Gravity.BOTTOM or Gravity.END, CountdownOverlay.resolveGravity("bottom-right"))
        assertEquals(Gravity.BOTTOM or Gravity.START, CountdownOverlay.resolveGravity("bottom-left"))
    }
}
