package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真用程序自带的 libmpv 解声音。 */
class AudioDecoderTest {
    private val temp = Files.createTempDirectory("pikseek-audio")
    private val decoder = MpvAudioDecoder(TestMedia.mpvDirectory, temp)

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun decodesTheWholeSoundtrackAsMonoAtTheAskedRate() {
        val samples = assertNotNull(decoder.decode(TestMedia.sampleMp4.toString(), 8_000, null, null, 20_000))
        // 样片 4 秒
        assertTrue(abs(samples.size - 32_000) <= 1_600, "samples=${samples.size}")
        assertTrue(samples.any { abs(it.toInt()) > 500 }, "样片有声音，不该全是静音")
        assertTrue(Files.list(temp).use { it.count() } == 0L, "中间文件读完就删")
    }

    @Test
    fun decodesAPart() {
        val samples = assertNotNull(decoder.decode(TestMedia.sampleMp4.toString(), 8_000, 1.0, 2.0, 20_000))
        assertTrue(abs(samples.size - 16_000) <= 1_600, "samples=${samples.size}")
    }

    @Test
    fun missingFileIsNull() {
        assertNull(decoder.decode(temp.resolve("nope.mp4").toString(), 8_000, null, null, 10_000))
    }

    /** 量速度：设了 PIKSEEK_LONG_SAMPLE（一个长视频的路径）才跑。 */
    @Test
    fun speedOnALongVideo() {
        val path = System.getenv("PIKSEEK_LONG_SAMPLE")?.takeIf { it.isNotBlank() } ?: return
        val started = System.nanoTime()
        val samples = assertNotNull(decoder.decode(Path.of(path).toString(), 8_000, 0.0, 300.0, 120_000))
        val ms = (System.nanoTime() - started) / 1_000_000
        println("解 300 秒声音：${samples.size} 个样本（${samples.size / 8000.0} 秒），用了 $ms ms")
    }
}
