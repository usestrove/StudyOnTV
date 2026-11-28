package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchGapTest {

    @Test
    fun recoversTwoSecondsAndIgnoresAtOrBelowOneSecond() {
        assertEquals(0L, WatchGap.recoverMs(100_000L, 100_000L, 100_500L, 100_500L))
        assertEquals(0L, WatchGap.recoverMs(100_000L, 100_000L, 101_000L, 101_000L))
        assertEquals(2_000L, WatchGap.recoverMs(100_000L, 100_000L, 102_000L, 102_000L))
    }

    @Test
    fun recoversTwentyTwoMinutesWithoutTenMinuteCliff() {
        val gap = 22 * 60 * 1000L
        assertEquals(gap, WatchGap.recoverMs(100_000L, 100_000L, 100_000L + gap, 100_000L + gap))
    }

    @Test
    fun capsNinetyMinutesAtSixtyMinuteMaximumInsteadOfReturningZero() {
        val gap = 90 * 60 * 1000L
        assertEquals(60 * 60 * 1000L, WatchGap.MAX_RECOVER_MS)
        assertEquals(WatchGap.MAX_RECOVER_MS, WatchGap.recoverMs(100_000L, 100_000L, 100_000L + gap, 100_000L + gap))
    }

    @Test
    fun excludesDeepSleepAndRejectsRebootOrInvalidBeat() {
        // 息屏深睡 30 分钟，但 CPU 仅清醒 2 分钟 -> 只补 2 分钟
        assertEquals(
            2 * 60 * 1000L,
            WatchGap.recoverMs(100_000L, 100_000L, 100_000L + 30 * 60 * 1000L, 100_000L + 2 * 60 * 1000L)
        )
        // 设备重启：单调时钟回退 -> 返回 0
        assertEquals(0L, WatchGap.recoverMs(100_000L, 100_000L, 30_000L, 100_000L))
        assertEquals(0L, WatchGap.recoverMs(100_000L, 100_000L, 100_000L, 30_000L))
        // 无效心跳 -> 返回 0
        assertEquals(0L, WatchGap.recoverMs(0L, 100_000L, 200_000L, 200_000L))
        assertEquals(0L, WatchGap.recoverMs(100_000L, 0L, 200_000L, 200_000L))
    }
}
