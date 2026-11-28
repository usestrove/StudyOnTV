package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPauseTest {

    @Test
    fun restoreVolumePlanRefreshesHalAfterUnmuteWhilePreservingFixedAndUserVolume() {
        // #116 fixed-volume box (volume=0): never write volume 0 before or after unmute.
        assertNull(MediaPause.computePreRestoreVolume(originallyMuted = false, touched = false, savedVolume = 0, current = 0))
        assertNull(MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = false, touched = false, savedVolume = 0, userVolume = 0, nowVolume = 0))

        // Originally muted by user: never unmute or write volume.
        assertNull(MediaPause.computePreRestoreVolume(originallyMuted = true, touched = false, savedVolume = 0, current = 0))
        assertNull(MediaPause.computePostUnmuteVolume(originallyMuted = true, preWrote = false, touched = false, savedVolume = 0, userVolume = 0, nowVolume = 10))

        // Firmware zeroed volume during mute: pre-write savedVolume, no redundant post-write.
        assertEquals(20, MediaPause.computePreRestoreVolume(originallyMuted = false, touched = false, savedVolume = 20, current = 0))
        assertNull(MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = true, touched = false, savedVolume = 20, userVolume = 0, nowVolume = 20))

        // #197 Sony BRAVIA: volume remained 23 while muted; perform idempotent post-unmute write of 23 to refresh audio_hal.
        assertNull(MediaPause.computePreRestoreVolume(originallyMuted = false, touched = false, savedVolume = 23, current = 23))
        assertEquals(23, MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = false, touched = false, savedVolume = 23, userVolume = 0, nowVolume = 23))

        // User lowered volume from 17 -> 10 during quiz:
        // If unmute bounced back to 17, restore 10; if it stayed at 10, keep 10; if user moved to 8, keep 8.
        assertEquals(10, MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = false, touched = true, savedVolume = 17, userVolume = 10, nowVolume = 17))
        assertEquals(10, MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = false, touched = true, savedVolume = 17, userVolume = 10, nowVolume = 10))
        assertEquals(8, MediaPause.computePostUnmuteVolume(originallyMuted = false, preWrote = false, touched = true, savedVolume = 17, userVolume = 10, nowVolume = 8))
    }
}
