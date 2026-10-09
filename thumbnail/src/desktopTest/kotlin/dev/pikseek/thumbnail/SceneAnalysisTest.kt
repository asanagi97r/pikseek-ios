package dev.pikseek.thumbnail

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 场景分点：合成的片子，每个场景一套颜色，场景内的帧随机拼（模拟换机位、人走动）。 */
class SceneAnalysisTest {
    private val width = 240
    private val height = 136

    // 每个场景的三种主色：背景、衣服、灯光
    private val palettes = listOf(
        intArrayOf(0x2A6F97, 0xE0C9A6, 0x103040),
        intArrayOf(0x7B2D8B, 0xD8B4A0, 0x301040),
        intArrayOf(0xC8C8C0, 0xE0C0A0, 0x606060),
        intArrayOf(0x1F7A3A, 0xD9B99B, 0x0B2E14),
        intArrayOf(0x8A5A2B, 0xE8D0B0, 0x3A2410),
        intArrayOf(0x202020, 0xD0B090, 0x505050),
    )

    /** 第 [scene] 个场景里、时刻 [timeMs] 的一帧。同一时刻总是同一帧。 */
    private fun frame(scene: Int, timeMs: Long): ThumbnailFrame {
        val random = Random(timeMs)
        val palette = palettes[scene % palettes.size]
        val pixels = IntArray(width * height) { palette[0] }
        // 几块随机位置、随机大小的「人」与「道具」
        repeat(3 + random.nextInt(3)) {
            val color = palette[1 + random.nextInt(2)]
            val w = 30 + random.nextInt(100)
            val h = 30 + random.nextInt(100)
            val x0 = random.nextInt(width - w)
            val y0 = random.nextInt(height - h)
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) pixels[y * width + x] = color
        }
        // 亮度随机起伏一点，再加点噪点
        val gain = 0.85 + random.nextDouble() * 0.3
        for (i in pixels.indices) {
            val p = pixels[i]
            fun channel(shift: Int) = (((p shr shift) and 0xFF) * gain + random.nextInt(-8, 9)).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return ThumbnailFrame(timeMs, width, height, pixels)
    }

    private fun sceneAt(timeMs: Long, boundaries: List<Long>): Int = boundaries.count { it <= timeMs }

    /** 取帧照转码流的样子：只落在 5 秒一个的关键帧上。 */
    private fun keyframeNear(timeMs: Long, boundaries: List<Long>): ThumbnailFrame {
        val time = timeMs / 5_000 * 5_000
        return frame(sceneAt(time, boundaries), time)
    }

    private fun previewFrames(durationMs: Long, boundaries: List<Long>): List<ThumbnailFrame> {
        val plan = ThumbnailPlan.of(durationMs, PreviewDensity.Medium)
        return (0 until plan.slotCount).map { keyframeNear(plan.slotTimeMs(it), boundaries) }
    }

    @Test
    fun scenesAreFoundAndRefinedToAKeyframe() = runBlocking {
        val duration = 100 * 60_000L
        // 不在 5 秒的整数倍上：细化只能落到边界之后的第一个关键帧
        val boundaries = listOf(12 * 60_000L + 2_000, 31 * 60_000L + 41_000, 55 * 60_000L + 17_000, 80 * 60_000L + 9_000)
        val frames = previewFrames(duration, boundaries)
        var fetched = 0
        val cuts = SceneAnalysis.analyze(frames, duration, fetch = { fetched++; keyframeNear(it, boundaries) })
        assertEquals(boundaries.size, cuts.size, "分点：$cuts")
        cuts.zip(boundaries).forEach { (cut, truth) ->
            assertTrue(cut >= truth && cut - truth <= SceneAnalysis.REFINE_PRECISION_MS, "分点 $cut 该落在 $truth 之后一个关键帧以内")
            assertEquals(0L, cut % 5_000, "落在关键帧上")
        }
        assertTrue(fetched <= boundaries.size * SceneAnalysis.MAX_REFINE_STEPS, "每个分点至多多取 ${SceneAnalysis.MAX_REFINE_STEPS} 帧，实际共 $fetched")
    }

    @Test
    fun withoutFetchingTheCutIsTheFirstPreviewOfTheNewScene() = runBlocking {
        val duration = 60 * 60_000L
        val boundaries = listOf(20 * 60_000L, 41 * 60_000L)
        val frames = previewFrames(duration, boundaries).sortedBy { it.timeMs }
        val cuts = SceneAnalysis.analyze(frames, duration, fetch = null)
        assertEquals(2, cuts.size, "分点：$cuts")
        cuts.zip(boundaries).forEach { (cut, truth) ->
            val firstAfter = frames.first { it.timeMs >= truth }.timeMs
            assertEquals(firstAfter, cut)
        }
    }

    @Test
    fun oneSceneHasNoCuts() = runBlocking {
        val duration = 90 * 60_000L
        val cuts = SceneAnalysis.analyze(previewFrames(duration, emptyList()), duration, fetch = null)
        assertTrue(cuts.isEmpty(), "一个场景不该切：$cuts")
    }

    @Test
    fun neverMoreThanTheCapAndNoSegmentShorterThanAMinute() = runBlocking {
        val duration = 120 * 60_000L
        // 每 4 分钟换一次场景，30 段：只给 10 个分点
        val boundaries = (1 until 30).map { it * 4 * 60_000L }
        val cuts = SceneAnalysis.analyze(previewFrames(duration, boundaries), duration, fetch = null)
        assertTrue(cuts.size <= SceneAnalysis.MAX_CUTS, "至多 ${SceneAnalysis.MAX_CUTS} 个：${cuts.size}")
        (listOf(0L) + cuts + duration).zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 50_000, "段太短：$a–$b") }
    }

    @Test
    fun aBlackTransitionCountsAsTheStartOfTheNewScene() = runBlocking {
        val duration = 60 * 60_000L
        val boundary = 30 * 60_000L + 12_000
        val black = ThumbnailFrame(0, width, height, IntArray(width * height) { 0xFF000000.toInt() })
        val cuts = SceneAnalysis.analyze(previewFrames(duration, listOf(boundary)), duration, fetch = { time ->
            val key = time / 5_000 * 5_000
            // 边界前 10 秒是黑场
            if (key in boundary - 10_000 until boundary) ThumbnailFrame(key, width, height, black.pixels) else keyframeNear(time, listOf(boundary))
        })
        assertEquals(1, cuts.size)
        assertTrue(abs(cuts.single() - (boundary - 10_000)) <= SceneAnalysis.REFINE_PRECISION_MS + 5_000, "分点 ${cuts.single()}")
    }
}
