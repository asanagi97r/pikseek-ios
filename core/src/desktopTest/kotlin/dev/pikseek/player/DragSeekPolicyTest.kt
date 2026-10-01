package dev.pikseek.player

import dev.pikseek.platform.DragSeekMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DragSeekPolicyTest {
    private val duration = 2 * 60 * 60_000L // 两小时

    /** 以每秒 [fractionPerSecond] 的速度拖 [seconds] 秒，每 16 毫秒一个指针事件，返回触发了几次跳转。 */
    private fun drag(policy: DragSeekPolicy, mode: DragSeekMode, fractionPerSecond: Double, seconds: Double, startMs: Long = 0): Int {
        var seeks = 0
        var now = startMs
        var target = duration / 4
        val steps = (seconds * 1000 / 16).toInt()
        repeat(steps) {
            now += 16
            target += (fractionPerSecond * duration * 0.016).toLong()
            if (policy.shouldSeek(mode, target, duration, now)) seeks++
        }
        return seeks
    }

    @Test
    fun offNeverSeeks() {
        assertEquals(0, drag(DragSeekPolicy(), DragSeekMode.Off, 0.02, 3.0))
    }

    @Test
    fun adaptiveFollowsASlowDragAtLowFrequency() {
        // 每秒 2%：三秒里跟了几次，但不是每个指针事件都跟
        val seeks = drag(DragSeekPolicy(), DragSeekMode.Adaptive, 0.02, 3.0)
        assertTrue(seeks in 4..8, "seeks=$seeks")
    }

    @Test
    fun adaptiveStaysQuietWhileSweepingFast() {
        // 每秒扫过半条进度条：一次都不跳，只看预览图
        assertEquals(0, drag(DragSeekPolicy(), DragSeekMode.Adaptive, 0.5, 2.0))
    }

    @Test
    fun adaptiveResumesWhenTheHandSlowsDown() {
        val policy = DragSeekPolicy()
        assertEquals(0, drag(policy, DragSeekMode.Adaptive, 0.5, 1.0))
        // 扫到大致的位置后慢下来细找：重新开始跟
        assertTrue(drag(policy, DragSeekMode.Adaptive, 0.01, 2.0, startMs = 1_000) >= 2)
    }

    @Test
    fun alwaysSeeksButStillThrottled() {
        val seeks = drag(DragSeekPolicy(), DragSeekMode.Always, 0.5, 1.5)
        // 每 150 毫秒至多一次，而不是每个指针事件一次
        assertTrue(seeks in 6..11, "seeks=$seeks")
    }

    @Test
    fun tinyMovesAreNotWorthASeek() {
        val policy = DragSeekPolicy()
        assertTrue(policy.shouldSeek(DragSeekMode.Always, 600_000, duration, 0))
        // 一秒之后只挪了 200 毫秒的进度：不跳
        assertFalse(policy.shouldSeek(DragSeekMode.Always, 600_200, duration, 1_000))
        assertTrue(policy.shouldSeek(DragSeekMode.Always, 630_000, duration, 2_000))
    }

    @Test
    fun resetForgetsThePreviousDrag() {
        val policy = DragSeekPolicy()
        assertTrue(policy.shouldSeek(DragSeekMode.Always, 600_000, duration, 0))
        policy.reset()
        // 新的一次拖动，哪怕落在同一处也照常跳
        assertTrue(policy.shouldSeek(DragSeekMode.Always, 600_000, duration, 50))
    }

    @Test
    fun unknownDurationNeverSeeks() {
        assertFalse(DragSeekPolicy().shouldSeek(DragSeekMode.Always, 1_000, 0, 0))
    }
}
