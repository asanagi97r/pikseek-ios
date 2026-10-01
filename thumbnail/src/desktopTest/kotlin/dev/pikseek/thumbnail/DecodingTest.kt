package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 真用程序自带的 libmpv 解码。 */
internal object TestMedia {
    val mpvDirectory: Path = Path.of(System.getProperty("pikseek.mpv.dir"))
    val sampleMp4: Path = Path.of(System.getProperty("pikseek.testdata"), "control-mpeg4-aac.mp4")

    /** 一帧中心一小块的平均颜色。 */
    fun centerColor(frame: ThumbnailFrame): Triple<Int, Int, Int> {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in frame.height / 2 - 4 until frame.height / 2 + 4) {
            for (x in frame.width / 2 - 4 until frame.width / 2 + 4) {
                val pixel = frame.pixels[y * frame.width + x]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        return Triple(red / count, green / count, blue / count)
    }

    fun assertColor(expected: Triple<Int, Int, Int>, frame: ThumbnailFrame, what: String) {
        val actual = centerColor(frame)
        val off = maxOf(abs(expected.first - actual.first), abs(expected.second - actual.second), abs(expected.third - actual.third))
        assertTrue(off <= 12, "$what：该是 $expected，解出来是 $actual")
    }
}

class MpvFrameGrabberTest {
    private val temp = Files.createTempDirectory("pikseek-grab")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun opensALocalFileAndSeeksToKeyframes() {
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            assertTrue(grabber.open(TestMedia.sampleMp4.toString()))
            assertEquals(4_000, grabber.durationMs)
            val first = assertNotNull(grabber.grab())
            // 缩到 240 宽，高度按比例取偶数
            assertEquals(240, first.width)
            assertTrue(first.height in 100..300 && first.height % 2 == 0)
            assertEquals(first.width * first.height, first.pixels.size)
            assertTrue(first.pixels.all { it ushr 24 == 0xFF })

            val later = assertNotNull(grabber.seekAndGrab(2.5))
            // 落在 2.5 秒之前最近的关键帧上
            assertTrue(later.timeMs in 1L..2_500L, "timeMs=${later.timeMs}")
            // 样片的画面在动：两帧不该一模一样
            assertTrue(!later.pixels.contentEquals(first.pixels))
        }
    }

    @Test
    fun canOpenStraightAtAPosition() {
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            assertTrue(grabber.open(TestMedia.sampleMp4.toString(), startSeconds = 3.0))
            val frame = assertNotNull(grabber.grab())
            assertTrue(frame.timeMs in 1_000L..3_000L, "timeMs=${frame.timeMs}")
        }
    }

    @Test
    fun missingFileFailsCleanlyAndTheGrabberStaysUsable() {
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            assertTrue(!grabber.open(temp.resolve("nope.mp4").toString(), timeoutMs = 5_000))
            assertTrue(grabber.open(TestMedia.sampleMp4.toString()))
            assertNotNull(grabber.grab())
        }
    }

    @Test
    fun decodesASingleKeyframeCutOutOfATransportStream() {
        val stream = SyntheticTs(gopCount = 5)
        val program = TsScan.findProgram(stream.bytes)!!
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            for (gop in listOf(3, 0, 4, 1)) {
                val keyframe = TsScan.findKeyframe(stream.bytes, program, stream.keyframeOffsets[gop].toInt())!!
                val file = temp.resolve("gop-$gop.ts")
                Files.write(file, TsScan.slice(stream.bytes, program, keyframe.offset, keyframe.endOffset))
                assertTrue(grabber.open(file.toString()), "第 $gop 个关键帧截出来的小文件打不开")
                val frame = assertNotNull(grabber.grab())
                assertEquals(240, frame.width)
                TestMedia.assertColor(stream.rgbOf(gop), frame, "第 $gop 个关键帧")
            }
        }
    }

    @Test
    fun twoGrabbersCoexist() {
        // 同一个进程里两个 mpv 实例互不相干：主播放器与缩略图引擎就是这样共处的
        MpvFrameGrabber(TestMedia.mpvDirectory).use { a ->
            MpvFrameGrabber(TestMedia.mpvDirectory, frameWidth = 160).use { b ->
                assertTrue(a.open(TestMedia.sampleMp4.toString()))
                assertTrue(b.open(TestMedia.sampleMp4.toString()))
                assertEquals(240, a.seekAndGrab(1.0)!!.width)
                assertEquals(160, b.seekAndGrab(3.0)!!.width)
            }
        }
    }
}

class TsSliceSourceTest {
    private val temp = Files.createTempDirectory("pikseek-slice")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun everyRequestedTimeYieldsTheFrameThatBelongsThere(): Unit = runBlocking {
        val stream = SyntheticTs(gopCount = 12)
        val reader = MemoryRangeReader(stream.bytes)
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            val source = TsSliceSource(reader, stream.durationMs, grabber, temp, "合成转码流")
            // 不按顺序要：先中间、再片尾、再片头
            for (time in listOf(31_000L, 58_000L, 1_000L, 12_500L, 44_000L, 27_000L)) {
                val frame = assertNotNull(source.frameNear(time), "time=$time")
                // 帧的时刻是它真实的时刻，离要的时刻不超过一个关键帧间隔
                assertEquals(0L, frame.timeMs % stream.gopMs, "应当落在关键帧上：${frame.timeMs}")
                assertTrue(abs(frame.timeMs - time) <= stream.gopMs, "要 $time，得到 ${frame.timeMs}")
                // 画面确实是那个时刻的：颜色对得上
                TestMedia.assertColor(stream.rgbOf((frame.timeMs / stream.gopMs).toInt()), frame, "time=$time")
            }
            // 每一帧只读它那一段：从估的位置到关键帧结束，不会超过一个关键帧间隔的数据再加头尾两块
            val longestGop = (stream.keyframeOffsets + stream.bytes.size.toLong()).zipWithNext { a, b -> b - a }.max()
            val budget = 6 * (2 * longestGop + 2 * reader.blockSize) + reader.blockSize
            assertTrue(source.networkBytes <= budget, "读了 ${source.networkBytes}，预算 $budget")
            // 每次读取都对齐到块、正好一块
            assertEquals(0, reader.misaligned)
            // 解码用的临时小文件用完即删
            assertTrue(Files.list(temp).use { it.count() } == 0L)
        }
    }

    @Test
    fun notATransportStreamIsReportedAsUnusable(): Unit = runBlocking {
        val notTs = MemoryRangeReader(ByteArray(TsScan.PACKET * 4000) { 7 })
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            val source = TsSliceSource(notTs, 60_000, grabber, temp, "不是 TS")
            assertFailsWith<SourceUnusableException> { source.frameNear(10_000) }
        }
        val oddLength = MemoryRangeReader(ByteArray(TsScan.PACKET * 4000 + 5))
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            assertFailsWith<SourceUnusableException> { TsSliceSource(oddLength, 60_000, grabber, temp, "长度不对").frameNear(0) }
        }
    }

    @Test
    fun seekingSourceWorksOnAnIndexedFile(): Unit = runBlocking {
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            val source = SeekingSource(TestMedia.sampleMp4.toString(), grabber, "本机文件")
            val a = assertNotNull(source.frameNear(3_000))
            val b = assertNotNull(source.frameNear(500))
            assertTrue(a.timeMs > b.timeMs)
            assertEquals(-1, source.networkBytes)
        }
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            assertFailsWith<SourceUnusableException> { SeekingSource(temp.resolve("nope.mkv").toString(), grabber, "没有的文件").frameNear(0) }
        }
    }

    @Test
    fun readFailureSurfacesAsAnErrorForThatFrameOnly(): Unit = runBlocking {
        val stream = SyntheticTs(gopCount = 6)
        val reader = MemoryRangeReader(stream.bytes)
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            val source = TsSliceSource(reader, stream.durationMs, grabber, temp, "合成转码流")
            assertNotNull(source.frameNear(5_000))
            reader.failing = true
            assertFailsWith<java.io.IOException> { source.frameNear(20_000) }
            reader.failing = false
            assertNotNull(source.frameNear(20_000))
        }
    }
}
