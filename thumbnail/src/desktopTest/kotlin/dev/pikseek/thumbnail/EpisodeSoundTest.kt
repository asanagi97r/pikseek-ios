package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 从转码流（TS）里按字节取一段的声音：样片 60 秒，全程静音，只有 40～42 秒一声「嘀」。
 * 取出来的指纹里，有声音的时刻该正好落在 40～42 秒。
 */
class EpisodeSoundTest {
    private val temp = Files.createTempDirectory("pikseek-episode-sound")
    private val ts: Path = Path.of(System.getProperty("pikseek.testdata"), "beep-at-40s.ts")
    private val sound = EpisodeSound(MpvAudioDecoder(TestMedia.mpvDirectory, temp), temp.toString())

    private class Memory(private val bytes: ByteArray) : ByteRangeReader {
        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    /** 有声音的那些时刻的头尾，毫秒。 */
    private fun loudSpan(print: AudioPrint): Pair<Long, Long>? {
        val loud = print.loud.indices.filter { print.loud[it] }
        if (loud.isEmpty()) return null
        return print.timeOf(loud.first()) to print.timeOf(loud.last())
    }

    @Test
    fun aSliceInTheMiddleKnowsWhereItStarts() = runBlocking {
        val bytes = Files.readAllBytes(ts)
        val print = assertNotNull(sound.fromTs(Memory(bytes), 60_000, 30_000, 20_000))
        // 前后各留 5 秒余量：从 25 秒起
        assertTrue(abs(print.startMs - 25_000) <= 2_000, "这一段从 ${print.startMs} 起")
        val (from, to) = assertNotNull(loudSpan(print), "40 秒的嘀声该在这一段里")
        assertTrue(abs(from - 40_000) <= 500, "嘀声开头在 $from")
        assertTrue(abs(to - 42_000) <= 500, "嘀声结尾在 $to")
        assertTrue(sound.networkBytes < bytes.size, "只读那一段，不读整个文件：读了 ${sound.networkBytes} / ${bytes.size}")
    }

    @Test
    fun theHeadStartsAtZeroAndIsSilent() = runBlocking {
        val print = assertNotNull(sound.fromTs(Memory(Files.readAllBytes(ts)), 60_000, 0, 20_000))
        assertEquals(0L, print.startMs)
        assertEquals(null, loudSpan(print), "开头 25 秒是静音")
    }

    @Test
    fun seekableFilesAreReadInPlace() = runBlocking {
        val print = assertNotNull(sound.fromLocation(ts.toString(), 38_000, 6_000))
        val (from, to) = assertNotNull(loudSpan(print))
        assertTrue(abs(from - 40_000) <= 500 && abs(to - 42_000) <= 500, "嘀声在 $from–$to")
    }
}
