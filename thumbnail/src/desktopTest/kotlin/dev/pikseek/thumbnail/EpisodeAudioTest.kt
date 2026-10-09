package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 认片头片尾：声音指纹与几集之间找共有的一段。声音是合成的；设了 PIKSEEK_LONG_SAMPLE 时再拿真片的声音拼一次。 */
class EpisodeAudioTest {
    private val rate = AudioPrint.SAMPLE_RATE

    /**
     * 一段「歌」：每 250 毫秒换一个三音和弦，底下铺一层随节拍起伏的宽频噪声（真的歌各个频带都有能量），带点鼓点。
     * 同一个种子出同一首。
     */
    private fun song(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        val out = ShortArray(seconds * rate)
        var chord = DoubleArray(3)
        var texture = 0.0
        var last = 0.0
        for (i in out.indices) {
            if (i % (rate / 4) == 0) chord = DoubleArray(3) { 200.0 + random.nextDouble() * 2300 }
            if (i % (rate / 8) == 0) texture = 500 + random.nextDouble() * 2500
            val t = i.toDouble() / rate
            last = 0.5 * last + 0.5 * (random.nextDouble() - 0.5) * 2 * texture
            var value = chord.sumOf { sin(2 * PI * it * t) } * 3000 + last
            if (i % (rate / 2) < 400) value += (random.nextDouble() - 0.5) * 8000
            out[i] = value.toInt().coerceIn(-32000, 32000).toShort()
        }
        return out
    }

    /** 一段「对白」：随机的噪声，响度一会儿高一会儿低。 */
    private fun talk(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        val out = ShortArray(seconds * rate)
        var level = 0.0
        var last = 0.0
        for (i in out.indices) {
            if (i % (rate / 5) == 0) level = random.nextDouble() * 6000
            // 一阶低通，听起来闷一点，像人声
            last = 0.7 * last + 0.3 * (random.nextDouble() - 0.5) * 2 * level
            out[i] = last.toInt().toShort()
        }
        return out
    }

    /** 同一段声音的「另一次编码」：音量变了、错开几个样本、加了底噪、高频被削了一点。 */
    private fun reencoded(samples: ShortArray, seed: Int): ShortArray {
        val random = Random(seed)
        val shift = 37
        val out = ShortArray(samples.size)
        var previous = 0.0
        for (i in out.indices) {
            val source = samples[(i + shift).coerceAtMost(samples.size - 1)] * 0.7
            val smooth = 0.6 * source + 0.4 * previous
            previous = source
            out[i] = (smooth + (random.nextDouble() - 0.5) * 200).toInt().coerceIn(-32000, 32000).toShort()
        }
        return out
    }

    private fun join(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    @Test
    fun theSharedSongIsFoundAtItsPlaceInBothEpisodes() {
        val op = song(90, seed = 7)
        val first = join(talk(40, 1), op, talk(170, 2))
        val second = join(talk(5, 3), reencoded(op, 4), talk(205, 5))
        val common = assertNotNull(EpisodeMatcher.common(AudioPrint.of(first, 0), AudioPrint.of(second, 0)))
        assertClose(40_000, common.inA.startMs, "第一集的片头开头")
        assertClose(130_000, common.inA.endMs, "第一集的片头结尾")
        assertClose(5_000, common.inB.startMs, "第二集的片头开头")
        assertClose(95_000, common.inB.endMs, "第二集的片头结尾")
    }

    @Test
    fun differentContentAndSilenceAreNotAnIntro() {
        val silence = ShortArray(60 * rate)
        val first = join(silence, talk(120, 1))
        val second = join(silence, talk(120, 2))
        assertNull(EpisodeMatcher.common(AudioPrint.of(first, 0), AudioPrint.of(second, 0)), "开头都静音、后面各说各的，不是片头")
        assertNull(EpisodeMatcher.common(AudioPrint.of(song(120, 1), 0), AudioPrint.of(song(120, 2), 0)), "两首不同的歌")
    }

    @Test
    fun tooShortSharedJingleIsIgnored() {
        val jingle = song(8, seed = 9)
        val first = join(talk(30, 1), jingle, talk(100, 2))
        val second = join(talk(50, 3), jingle, talk(80, 4))
        assertNull(EpisodeMatcher.common(AudioPrint.of(first, 0), AudioPrint.of(second, 0)), "8 秒的台标音乐不算片头")
    }

    @Test
    fun episodesGetIntroAndOutroWithTimesInTheirOwnTimeline() {
        val op = song(80, seed = 11)
        val ed = song(70, seed = 12)
        // 每集 24 分钟：开头取前 5 分钟，结尾取最后 4 分钟（从 20 分钟起）
        val episodes = (0 until 3).map { n ->
            val coldOpen = 10 + n * 20
            val head = join(talk(coldOpen, 100 + n), reencoded(op, 200 + n), talk(300 - coldOpen - 80, 300 + n))
            val tail = join(talk(60 + n * 5, 400 + n), reencoded(ed, 500 + n), talk(240 - 60 - n * 5 - 70, 600 + n))
            EpisodeMatcher.Episode("ep$n", AudioPrint.of(head, 0), AudioPrint.of(tail, 20 * 60_000L))
        }
        val found = EpisodeMatcher.find(episodes)
        assertEquals(3, found.size)
        for (n in 0 until 3) {
            val result = found.getValue("ep$n")
            val intro = assertNotNull(result.intro, "第 $n 集的片头")
            assertClose((10 + n * 20) * 1000L, intro.startMs, "第 $n 集片头开头")
            assertClose((10 + n * 20 + 80) * 1000L, intro.endMs, "第 $n 集片头结尾")
            val outro = assertNotNull(result.outro, "第 $n 集的片尾")
            assertClose(20 * 60_000L + (60 + n * 5) * 1000L, outro.startMs, "第 $n 集片尾开头")
        }
    }

    @Test
    fun aSingleEpisodeHasNothingToCompareWith() {
        val only = EpisodeMatcher.Episode("only", AudioPrint.of(song(60, 1), 0), null)
        val result = EpisodeMatcher.find(listOf(only)).getValue("only")
        assertNull(result.intro)
        assertNull(result.outro)
    }

    /** 真片的声音拼两集：同一段 90 秒放在两集不同的位置，前后是片中别处的声音。设了 PIKSEEK_LONG_SAMPLE 才跑。 */
    @Test
    fun realSoundtrackSpliced() {
        val path = System.getenv("PIKSEEK_LONG_SAMPLE")?.takeIf { it.isNotBlank() } ?: return
        val temp = Files.createTempDirectory("pikseek-episode")
        try {
            val decoder = MpvAudioDecoder(TestMedia.mpvDirectory, temp)
            fun piece(start: Int, seconds: Int) = assertNotNull(decoder.decode(Path.of(path).toString(), rate, start.toDouble(), seconds.toDouble(), 60_000))
            val op = piece(3_000, 90)
            val first = join(piece(100, 60), op, piece(200, 150))
            val second = join(piece(1_000, 20), reencoded(op, 1), piece(1_100, 190))
            val started = System.nanoTime()
            val common = assertNotNull(EpisodeMatcher.common(AudioPrint.of(first, 0), AudioPrint.of(second, 0)))
            println("真片声音拼的两集：第一集 ${common.inA.startMs}–${common.inA.endMs}，第二集 ${common.inB.startMs}–${common.inB.endMs}，用了 ${(System.nanoTime() - started) / 1_000_000} ms")
            assertClose(60_000, common.inA.startMs, "第一集开头")
            assertClose(150_000, common.inA.endMs, "第一集结尾")
            assertClose(20_000, common.inB.startMs, "第二集开头")
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private fun assertClose(expected: Long, actual: Long, what: String) {
        assertTrue(abs(expected - actual) <= 1_500, "$what：该在 $expected 附近，实际 $actual")
    }
}
