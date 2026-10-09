package dev.pikseek.thumbnail

import kotlin.math.min
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * 取一集开头、结尾的声音，算成 [AudioPrint]，认片头片尾用。
 *
 * - 网盘的转码流（TS）：按码率估出那一段在哪些字节，连同前后各几秒的余量整段读下来，存成临时文件交给解码器；
 *   这一段从哪个时刻开始，看它里面第一个视频时间戳。
 * - 有索引的（原画、本机文件）：解码器自己跳过去，只读那一段。
 *
 * @param tempDirectory 读下来的那段 TS 临时放哪，解完就删
 */
class EpisodeSound(private val decoder: AudioDecoder, private val tempDirectory: String) {
    private val fetched = atomic(0L)

    /** 一共从网络读了多少字节。 */
    val networkBytes: Long get() = fetched.value

    /** 转码流 [reader]（全片 [durationMs]）里 [startMs] 起 [lengthMs] 长的一段的指纹。取不到为 null。 */
    suspend fun fromTs(reader: ByteRangeReader, durationMs: Long, startMs: Long, lengthMs: Long): AudioPrint? {
        if (durationMs <= 0 || reader.size < TsScan.PACKET * 16 || reader.size % TsScan.PACKET != 0L) return null
        val bytesPerMs = reader.size.toDouble() / durationMs
        val margin = (MARGIN_MS * bytesPerMs).toLong()
        // 从包边界起：整条流从 0 起就是一个个 188 字节的包
        val from = packetAligned((startMs * bytesPerMs).toLong() - margin, reader.size)
        val to = min(reader.size, ((startMs + lengthMs) * bytesPerMs).toLong() + margin)
        if (to - from < TsScan.PACKET * 16) return null

        // 节目表与起始时间戳从流的开头读；这一段的开头时刻按它自己的第一个视频时间戳算
        val head = read(reader, 0, HEAD_BYTES)
        val program = TsScan.findProgram(head) ?: return null
        val streamStart = TsScan.firstVideoPtsMs(head, program) ?: 0L

        val file = Path(tempDirectory, "episode-${serial.incrementAndGet()}.ts")
        try {
            var sliceStartMs: Long? = if (from == 0L) 0L else null
            withContext(Dispatchers.IO) { SystemFileSystem.createDirectories(Path(tempDirectory)) }
            val sink = withContext(Dispatchers.IO) { SystemFileSystem.sink(file).buffered() }
            try {
                var offset = from
                while (offset < to) {
                    // 几块一起读，按顺序写
                    val chunks = coroutineScope {
                        (0 until PARALLEL).mapNotNull { index ->
                            val at = offset + index.toLong() * CHUNK
                            if (at >= to) null else async { read(reader, at, min(CHUNK.toLong(), to - at).toInt()) }
                        }.awaitAll()
                    }
                    for (chunk in chunks) {
                        if (sliceStartMs == null) {
                            sliceStartMs = TsScan.firstVideoPtsMs(chunk, program)?.let { TsScan.elapsedMs(streamStart, it) }
                        }
                        withContext(Dispatchers.IO) { sink.write(chunk) }
                        offset += chunk.size
                        if (chunk.size < CHUNK) {
                            offset = to
                            break
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { sink.close() }
            }
            val start = sliceStartMs ?: (from / bytesPerMs).toLong()
            val samples = withContext(Dispatchers.IO) { decoder.decode(file.toString(), AudioPrint.SAMPLE_RATE, null, null, DECODE_TIMEOUT_MS) }
                ?: return null
            return AudioPrint.of(samples, start)
        } finally {
            withContext(Dispatchers.IO) { runCatching { SystemFileSystem.delete(file, mustExist = false) } }
        }
    }

    /** 有索引的文件（本机路径或本机代理的地址）里 [startMs] 起 [lengthMs] 长的一段的指纹。取不到为 null。 */
    suspend fun fromLocation(location: String, startMs: Long, lengthMs: Long): AudioPrint? = withContext(Dispatchers.IO) {
        decoder.decode(location, AudioPrint.SAMPLE_RATE, startMs / 1000.0, lengthMs / 1000.0, DECODE_TIMEOUT_MS)
            ?.let { AudioPrint.of(it, startMs) }
    }

    private suspend fun read(reader: ByteRangeReader, offset: Long, length: Int): ByteArray =
        reader.read(offset, length).also { fetched.addAndGet(it.size.toLong()) }

    private fun packetAligned(offset: Long, size: Long): Long {
        val clamped = offset.coerceIn(0, (size - TsScan.PACKET).coerceAtLeast(0))
        return clamped - clamped % TsScan.PACKET
    }

    companion object {
        /** 片头在开头这么长里找：片头前常有一段「前情」或开场戏。 */
        const val HEAD_MS = 5 * 60_000L

        /** 片尾在结尾这么长里找：片尾曲后面常跟着下集预告。 */
        const val TAIL_MS = 4 * 60_000L

        /** 太长的视频不是剧集（电影、长片），不认片头片尾，免得白读几十 MB。 */
        const val MAX_EPISODE_MS = 75 * 60_000L

        /** 开头那段：从 0 起，至多 [HEAD_MS]，不超过全片的三成。 */
        fun headSpan(durationMs: Long): MediaMarks.Span = MediaMarks.Span(0, min(HEAD_MS, durationMs * 3 / 10))

        /** 结尾那段：至多 [TAIL_MS]，不超过全片的四分之一。 */
        fun tailSpan(durationMs: Long): MediaMarks.Span {
            val length = min(TAIL_MS, durationMs / 4)
            return MediaMarks.Span(durationMs - length, durationMs)
        }

        private val serial = atomic(0L)
        private const val MARGIN_MS = 5_000L
        private const val HEAD_BYTES = 512 * 1024
        // 约 1 MB，取 188 的整数倍：每一块都从包边界起
        private const val CHUNK = TsScan.PACKET * 5_578
        private const val PARALLEL = 3
        private const val DECODE_TIMEOUT_MS = 120_000L
    }
}
