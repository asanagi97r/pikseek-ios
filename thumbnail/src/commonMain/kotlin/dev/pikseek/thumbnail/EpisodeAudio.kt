package dev.pikseek.thumbnail

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 一段声音的指纹：每 [HOP] 个样本（32 毫秒）一个 32 位的小指纹。做法照 Haitsma 与 Kalker 的那篇：
 * 300～3000 Hz 分成 33 个按对数排开的频带，第 m 位记「这一刻第 m 带比第 m+1 带多出的能量，比上一刻是多了还是少了」。
 * 同一段音乐换了编码、音量、码率，这些位大多不变；不同的声音之间约一半的位不同。
 *
 * 太安静的时刻另记下来（[loud] 为 false）：静音的指纹全是 0，两段静音会被当成一模一样。
 *
 * @param startMs 第一个样本在片中的时刻
 */
class AudioPrint(val startMs: Long, val bits: IntArray, val loud: BooleanArray) {
    val size: Int get() = bits.size

    /** 第 [index] 个小指纹在片中的时刻。 */
    fun timeOf(index: Int): Long = startMs + (index.toDouble() * HOP * 1000 / SAMPLE_RATE).roundToLong()

    companion object {
        /** 解声音时要的采样率：8 kHz 够了，指纹只看 3 kHz 以下。 */
        const val SAMPLE_RATE = 8_000
        const val FRAME = 1_024
        const val HOP = 256
        private const val BANDS = 33
        private const val LOW_HZ = 300.0
        private const val HIGH_HZ = 3_000.0

        /** 一帧的平均振幅（16 位样本）低于这个算静音。 */
        private const val SILENCE = 150.0

        val hopMs: Double get() = HOP * 1000.0 / SAMPLE_RATE

        /** 由 [SAMPLE_RATE] 的单声道样本算指纹。 */
        fun of(samples: ShortArray, startMs: Long): AudioPrint {
            val frames = if (samples.size < FRAME) 0 else (samples.size - FRAME) / HOP + 1
            val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
            // 各频带落在 FFT 的哪几格
            val binHz = SAMPLE_RATE.toDouble() / FRAME
            val edges = IntArray(BANDS + 1) { band ->
                val hz = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(band.toDouble() / BANDS)
                (hz / binHz).toInt()
            }
            val real = DoubleArray(FRAME)
            val imaginary = DoubleArray(FRAME)
            val bits = IntArray(frames)
            val loud = BooleanArray(frames)
            var previous = DoubleArray(BANDS)
            for (frame in 0 until frames) {
                val offset = frame * HOP
                var amplitude = 0.0
                for (i in 0 until FRAME) {
                    val sample = samples[offset + i].toDouble()
                    amplitude += kotlin.math.abs(sample)
                    real[i] = sample * window[i]
                    imaginary[i] = 0.0
                }
                loud[frame] = amplitude / FRAME >= SILENCE
                fft(real, imaginary)
                val energy = DoubleArray(BANDS)
                for (band in 0 until BANDS) {
                    var sum = 0.0
                    for (bin in edges[band] until max(edges[band] + 1, edges[band + 1])) {
                        sum += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
                    }
                    // 取对数：响的段与轻的段一样看待
                    energy[band] = ln(1.0 + sum)
                }
                if (frame > 0) {
                    var value = 0
                    for (m in 0 until BANDS - 1) {
                        val now = energy[m] - energy[m + 1]
                        val before = previous[m] - previous[m + 1]
                        if (now - before > 0) value = value or (1 shl m)
                    }
                    bits[frame] = value
                }
                previous = energy
            }
            return AudioPrint(startMs, bits, loud)
        }

        /** 原地的基 2 FFT，长度是 2 的幂。 */
        private fun fft(real: DoubleArray, imaginary: DoubleArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = real[i]; real[i] = real[j]; real[j] = t
                    t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2 * PI / length
                val stepReal = cos(angle)
                val stepImaginary = sin(angle)
                var start = 0
                while (start < n) {
                    var wReal = 1.0
                    var wImaginary = 0.0
                    for (k in 0 until length / 2) {
                        val a = start + k
                        val b = a + length / 2
                        val tReal = real[b] * wReal - imaginary[b] * wImaginary
                        val tImaginary = real[b] * wImaginary + imaginary[b] * wReal
                        real[b] = real[a] - tReal
                        imaginary[b] = imaginary[a] - tImaginary
                        real[a] += tReal
                        imaginary[a] += tImaginary
                        val next = wReal * stepReal - wImaginary * stepImaginary
                        wImaginary = wReal * stepImaginary + wImaginary * stepReal
                        wReal = next
                    }
                    start += length
                }
                length = length shl 1
            }
        }
    }
}

/**
 * 认片头片尾：同一季每一集的片头曲、片尾曲几乎一模一样。拿两集开头（或结尾）的声音指纹，
 * 在所有错开的位置上比，找出最长的一段「一直对得上」的，就是两集共有的片头（片尾）。不用训练，也不用标注。
 */
object EpisodeMatcher {
    /** 片头片尾至少这么长：再短的多半是台标、转场音效。 */
    const val MIN_MS = 15_000L

    /** 片头片尾至多这么长。 */
    const val MAX_MS = 180_000L

    /** 一对小指纹至多这么多位不同才算对得上（32 位里；不相干的声音平均差 16 位）。 */
    private const val MAX_BIT_ERRORS = 10

    /** 一段里至少这个比例的时刻对得上。 */
    private const val MIN_DENSITY = 0.5

    /** 连着这么多个有声音的时刻（约 1 秒）对不上，这一段就断了。 */
    private const val MAX_GAP = 32

    /** 对上的时刻至少占到最短长度的这个比例：一段里大半是静音、只零星对上几下的不算。 */
    private const val MIN_MATCHED_SHARE = 0.3

    /** [a]、[b] 里共有的一段：在 [a] 里的范围、在 [b] 里的范围，毫秒。 */
    class Common(val inA: MediaMarks.Span, val inB: MediaMarks.Span)

    /** [a] 与 [b] 共有的最长一段，长度在 [MIN_MS] 与 [MAX_MS] 之间；没有为 null。 */
    fun common(a: AudioPrint, b: AudioPrint): Common? {
        val minFrames = (MIN_MS / AudioPrint.hopMs).toInt()
        val maxFrames = (MAX_MS / AudioPrint.hopMs).toInt()
        var bestLength = 0
        var bestStart = -1
        var bestShift = 0
        // shift：a 的第 i 个对 b 的第 i - shift 个
        for (shift in -(b.size - minFrames)..(a.size - minFrames)) {
            val from = max(0, shift)
            val to = min(a.size, b.size + shift)
            if (to - from < minFrames) continue
            var runStart = -1
            var lastMatch = -1
            var matches = 0
            // 这一段里对不上的（只数两边都有声音的时刻）：一共几个、自上一次对上以来连着几个
            var missesAtLastMatch = 0
            var misses = 0
            var streak = 0
            fun close() {
                if (runStart < 0) return
                if (lastMatch - runStart + 1 <= bestLength) return
                val dense = matches >= (matches + missesAtLastMatch) * MIN_DENSITY && matches >= minFrames * MIN_MATCHED_SHARE
                if (!dense) return
                // 两头可能各粘着几下碰巧对上的：修到「一小段里大半对得上」的地方为止
                val (start, end) = trim(a, b, shift, runStart, lastMatch)
                val length = end - start + 1
                if (length in minFrames..maxFrames && length > bestLength) {
                    bestLength = length
                    bestStart = start
                    bestShift = shift
                }
            }
            for (i in from until to) {
                val j = i - shift
                // 有一边没声音：既不算对上也不算没对上。片头曲里也有一两秒的停顿，不该因此断开
                if (!a.loud[i] || !b.loud[j]) continue
                if ((a.bits[i] xor b.bits[j]).countOneBits() > MAX_BIT_ERRORS) {
                    if (runStart >= 0) {
                        misses++
                        if (++streak > MAX_GAP) {
                            close()
                            runStart = -1
                        }
                    }
                    continue
                }
                if (runStart < 0) {
                    runStart = i
                    matches = 0
                    misses = 0
                }
                streak = 0
                lastMatch = i
                matches++
                missesAtLastMatch = misses
            }
            close()
        }
        if (bestStart < 0) return null
        val endA = bestStart + bestLength
        return Common(
            inA = MediaMarks.Span(a.timeOf(bestStart), a.timeOf(endA)),
            inB = MediaMarks.Span(b.timeOf(bestStart - bestShift), b.timeOf(endA - bestShift)),
        )
    }

    private fun matched(a: AudioPrint, b: AudioPrint, i: Int, j: Int): Boolean? {
        if (!a.loud[i] || !b.loud[j]) return null
        return (a.bits[i] xor b.bits[j]).countOneBits() <= MAX_BIT_ERRORS
    }

    /**
     * 一段的两头往里修：开头挪到第一个「往后 [EDGE_WINDOW] 个有声音的时刻里至少一半对得上」的对上点，结尾同理往前。
     * 不相干的声音也有约 2% 的时刻碰巧对上，一段的两头常粘着几下这种，片头片尾的边界就会早出、晚出几秒。
     */
    private fun trim(a: AudioPrint, b: AudioPrint, shift: Int, start: Int, end: Int): Pair<Int, Int> {
        fun dense(from: Int, step: Int): Boolean {
            var seen = 0
            var hits = 0
            var i = from
            while (i in start..end && seen < EDGE_WINDOW) {
                when (matched(a, b, i, i - shift)) {
                    true -> { seen++; hits++ }
                    false -> seen++
                    null -> {}
                }
                i += step
            }
            return seen > 0 && hits * 2 >= seen
        }
        var first = start
        while (first < end && (matched(a, b, first, first - shift) != true || !dense(first, 1))) first++
        var last = end
        while (last > first && (matched(a, b, last, last - shift) != true || !dense(last, -1))) last--
        return first to last
    }

    // 修两头时看多大一小段：约半秒
    private const val EDGE_WINDOW = 16

    /** 一集：开头一段与结尾一段的指纹，没解出来的为 null。 */
    class Episode(val id: String, val head: AudioPrint?, val tail: AudioPrint?)

    /** 一集认出来的片头、片尾，没有的为 null。 */
    class Found(val intro: MediaMarks.Span?, val outro: MediaMarks.Span?)

    /**
     * 一个文件夹里的几集，按集数排好。每一集与前后各 [neighbours] 集比，取找到的最长那段；
     * 片头只在开头那段里找，片尾只在结尾那段里找。少于两集时什么也认不出。
     */
    fun find(episodes: List<Episode>, neighbours: Int = 2): Map<String, Found> {
        val intros = arrayOfNulls<MediaMarks.Span>(episodes.size)
        val outros = arrayOfNulls<MediaMarks.Span>(episodes.size)
        fun keepLonger(into: Array<MediaMarks.Span?>, index: Int, span: MediaMarks.Span) {
            if (span.lengthMs > (into[index]?.lengthMs ?: 0)) into[index] = span
        }
        // 每一对只比一次，结果两边都用上
        for (i in episodes.indices) {
            for (j in i + 1..minOf(i + neighbours, episodes.lastIndex)) {
                val a = episodes[i]
                val b = episodes[j]
                if (a.head != null && b.head != null) common(a.head, b.head)?.let { keepLonger(intros, i, it.inA); keepLonger(intros, j, it.inB) }
                if (a.tail != null && b.tail != null) common(a.tail, b.tail)?.let { keepLonger(outros, i, it.inA); keepLonger(outros, j, it.inB) }
            }
        }
        return episodes.withIndex().associate { (index, episode) -> episode.id to Found(intros[index], outros[index]) }
    }
}
