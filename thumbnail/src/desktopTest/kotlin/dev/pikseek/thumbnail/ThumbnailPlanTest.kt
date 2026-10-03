package dev.pikseek.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailPlanTest {
    private fun minutes(value: Int) = value * 60_000L

    @Test
    fun mediumDensityFollowsTheLengthTable() {
        assertEquals(60, ThumbnailPlan.of(minutes(20), PreviewDensity.Medium).slotCount)
        assertEquals(60, ThumbnailPlan.of(minutes(30), PreviewDensity.Medium).slotCount)
        assertEquals(90, ThumbnailPlan.of(minutes(45), PreviewDensity.Medium).slotCount)
        assertEquals(120, ThumbnailPlan.of(minutes(100), PreviewDensity.Medium).slotCount)
        assertEquals(180, ThumbnailPlan.of(minutes(150), PreviewDensity.Medium).slotCount)
        assertEquals(240, ThumbnailPlan.of(minutes(200), PreviewDensity.Medium).slotCount)
    }

    @Test
    fun lowHalvesAndHighDoubles() {
        assertEquals(60, ThumbnailPlan.of(minutes(100), PreviewDensity.Low).slotCount)
        assertEquals(240, ThumbnailPlan.of(minutes(100), PreviewDensity.High).slotCount)
    }

    @Test
    fun shortClipsAreNotOversampled() {
        // 一分钟的片子做 60 张没有意义：转码流 5 秒才一个关键帧
        val plan = ThumbnailPlan.of(60_000, PreviewDensity.Medium)
        assertEquals(20, plan.slotCount)
        assertTrue(plan.intervalMs >= ThumbnailPlan.MIN_INTERVAL_MS)
        assertEquals(8, ThumbnailPlan.of(5_000, PreviewDensity.High).slotCount)
    }

    @Test
    fun slotsCoverTheTimelineEvenly() {
        val plan = ThumbnailPlan(100_000, 10)
        assertEquals(5_000, plan.slotTimeMs(0))
        assertEquals(95_000, plan.slotTimeMs(9))
        assertEquals(0, plan.slotAt(0))
        assertEquals(3, plan.slotAt(39_999))
        assertEquals(4, plan.slotAt(40_000))
        assertEquals(9, plan.slotAt(100_000))
        assertEquals(9, plan.slotAt(999_999))
    }

    @Test
    fun orderStartsNearTheCurrentPositionThenCoarseThenFillsIn() {
        val plan = ThumbnailPlan.of(minutes(100), PreviewDensity.Medium)
        val order = plan.order(positionMs = minutes(40))
        // 每一格恰好一次
        assertEquals(plan.slotCount, order.size)
        assertEquals((0 until plan.slotCount).toSet(), order.toSet())

        // 先是当前位置与左右各两格
        val here = plan.slotAt(minutes(40))
        assertEquals(listOf(here, here - 1, here + 1, here - 2, here + 2), order.take(5))

        // 接着是粗略预览的那些格，全部排在其余各格之前
        val coarse = plan.coarseSlots.toSet()
        assertEquals(ThumbnailPlan.COARSE_COUNT, coarse.size)
        val lastCoarseAt = order.indexOfLast { it in coarse }
        assertTrue(lastCoarseAt < 5 + coarse.size, "粗略预览应当在前 ${5 + coarse.size} 个里做完，实际到第 $lastCoarseAt 个")
    }

    @Test
    fun anyPrefixOfTheCoarsePhaseIsSpreadOverTheWholeTimeline() {
        val plan = ThumbnailPlan.of(minutes(120), PreviewDensity.Medium)
        val coarseOrder = plan.order(0).filter { it in plan.coarseSlots }
        // 才做了 6 张，就已经左中右都有，而不是全挤在片头
        val firstSix = coarseOrder.take(6).map { plan.slotTimeMs(it).toDouble() / plan.durationMs }
        assertTrue(firstSix.any { it < 0.2 }, firstSix.toString())
        assertTrue(firstSix.any { it in 0.4..0.6 }, firstSix.toString())
        assertTrue(firstSix.any { it > 0.8 }, firstSix.toString())
    }

    @Test
    fun bisectionIsAPermutationWithEndsAndMiddleFirst() {
        for (count in listOf(1, 2, 3, 7, 24, 25, 120, 241)) {
            val order = ThumbnailPlan.bisection(count)
            assertEquals((0 until count).toList(), order.sorted(), "count=$count")
        }
        assertEquals(listOf(0, 8, 4, 2, 6, 1, 3, 5, 7), ThumbnailPlan.bisection(9))
    }

    @Test
    fun aroundListsNeighboursNearestFirstAndStaysInRange() {
        val plan = ThumbnailPlan(100_000, 10)
        assertEquals(listOf(5, 4, 6, 3, 7), plan.around(55_000))
        assertEquals(listOf(0, 1, 2), plan.around(0))
        assertFalse(plan.around(99_999).any { it > 9 })
    }
}

class ThumbnailFrameTest {
    private fun solid(rgb: Int) = ThumbnailFrame(0, 16, 16, IntArray(256) { 0xFF000000.toInt() or rgb })

    @Test
    fun plainBlackIsBlackButDarkScenesAreNot() {
        assertTrue(solid(0x000000).isBlack())
        assertTrue(solid(0x050505).isBlack())
        assertFalse(solid(0x404040).isBlack())
        // 夜景：整体很暗，但有几处亮的。平均亮度低，方差不低
        val night = IntArray(256) { index -> 0xFF000000.toInt() or if (index % 16 == 0) 0xE0E0E0 else 0x020202 }
        assertFalse(ThumbnailFrame(0, 16, 16, night).isBlack())
    }

    @Test
    fun fingerprintKeyIgnoresFileIdWhenContentHashIsKnown() {
        val a = MediaFingerprint("file-1", "GCID", 1000, 60_000)
        val moved = MediaFingerprint("file-2", "GCID", 1000, 60_000)
        val replaced = MediaFingerprint("file-1", "GCID", 1001, 60_000)
        assertEquals(a.cacheKey(), moved.cacheKey())
        assertTrue(a.cacheKey() != replaced.cacheKey())
        // 各处读出的时长差一两秒，仍是同一个视频；gcid 大小写不同也是
        assertEquals(a.cacheKey(), MediaFingerprint("file-3", "gcid", 1000, 61_000).cacheKey())
        // 没有内容哈希（本机文件）时按文件标识
        assertTrue(MediaFingerprint("x", "", 1, 1).cacheKey() != MediaFingerprint("y", "", 1, 1).cacheKey())
        assertEquals(32, a.cacheKey().length)
    }
}
