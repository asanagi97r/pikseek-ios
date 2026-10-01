package dev.pikseek.thumbnail

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * 缩略图的画面来源：给一个时刻，交回那附近的一帧。引擎不关心它背后是网盘的转码流、原画还是本机文件。
 *
 * 实现要自己保证不打扰主播放器：读网络用后台优先级，解码用自己的解码器实例。
 */
interface ThumbnailSource : AutoCloseable {
    /** 给人看的说明，例如「480P 转码流」。 */
    val description: String

    /** 同时取几帧还有好处。解码是串行的，能并行的只有取数据那一段。 */
    val maxParallel: Int

    /** 为取帧从网络读了多少字节；不知道（由 mpv 自己读）时为 -1。 */
    val networkBytes: Long

    /**
     * [timeMs] 附近的一帧，帧的真实时刻在返回值里。取不到返回 null。
     * @throws SourceUnusableException 这个来源整个用不了（不是 TS、打不开），引擎换下一个
     */
    suspend fun frameNear(timeMs: Long): ThumbnailFrame?
}

/** 这个来源整个用不了，引擎应当换下一个来源，而不是一格一格地失败下去。 */
class SourceUnusableException(message: String) : Exception(message)

/** 按偏移读一段字节。由程序那一侧接到网盘的读取上（后台优先级），测试里接到内存。 */
interface ByteRangeReader {
    val size: Long

    /**
     * 底下那一层取数据的块大小。它按块取：碰到块里的一个字节，整块都会下回来。
     * 所以这里的读取都对齐到块，一次正好一块，不跨两块。
     */
    val blockSize: Int get() = DEFAULT_BLOCK_SIZE

    /** 读 [offset] 起的至多 [length] 字节，到了末尾就少给。 */
    suspend fun read(offset: Long, length: Int): ByteArray

    companion object {
        /** PikPak SDK 的块：256 KiB。 */
        const val DEFAULT_BLOCK_SIZE = 256 * 1024
    }
}

/**
 * 没有索引的 MPEG-TS（PikPak 的转码流）：不让解码器在流里跳来跳去，而是自己算好位置，
 * 把一个关键帧的数据取回来，存成只有一帧的小文件，交给解码器从头读。
 *
 * 每取一帧：按「时刻到字节」的对照估一个位置 → 从那里往后找第一个关键帧 → 读到这一帧结束 →
 * 截成小文件 → 解码。读到的关键帧带着真实的时间戳，记进对照表，后面的估计越来越准
 * （转码流的码率只是大致恒定，一开始按全片平均码率估，偏差可能有十几秒）。
 *
 * 取数据可以两路并行，解码经一把锁串行。
 */
class TsSliceSource(
    private val reader: ByteRangeReader,
    private val durationMs: Long,
    private val grabber: FrameGrabber,
    private val tempDirectory: String,
    override val description: String,
) : ThumbnailSource {
    override val maxParallel: Int = 2
    private val fetched = atomic(0L)
    override val networkBytes: Long get() = fetched.value

    private val prepareLock = Mutex()
    private val decodeLock = Mutex()
    private var program: TsScan.Program? = null
    private var streamStartMs = 0L
    private val serial = atomic(0L)
    private val block = reader.blockSize.coerceAtLeast(TsScan.PACKET * 16)

    // 已知的「时刻 → 关键帧所在的字节位置」，两端先按片头片尾放上
    private val knownLock = SynchronizedObject()
    private val knownTimes = ArrayList<Long>()
    private val knownOffsets = ArrayList<Long>()

    override suspend fun frameNear(timeMs: Long): ThumbnailFrame? {
        val program = prepare()
        var estimate = packetAligned(offsetFor((timeMs - LEAD_MS).coerceAtLeast(0)))
        var window = Window(estimate)
        var keyframe: TsScan.Keyframe? = null
        for (round in 0..BACKTRACKS) {
            window = Window(estimate)
            window.extend()
            // 窗口从块的边界起，比估的位置靠前：只认估的位置之后的关键帧
            val from = (estimate - window.origin).coerceAtLeast(0).toInt()
            keyframe = TsScan.findKeyframe(window.packets, program, from)
            // 关键帧不在这一块里就接着往后读，直到找到或读过了头
            while (keyframe == null && window.packets.size < MAX_SCAN) {
                // 上一块末尾的几个包当时看不全后面的数据，连它们一起重看
                val scanFrom = maxOf(from, window.packets.size - RESCAN_PACKETS * TsScan.PACKET)
                if (!window.extend()) break
                keyframe = TsScan.findKeyframe(window.packets, program, scanFrom)
            }
            if (keyframe != null) {
                if (round > 0) {
                    // 这是退回来找的：要的是流末尾前最后一个关键帧。把后面的读完，取最后一个
                    while (window.packets.size < MAX_SCAN && window.extend()) {
                    }
                    var last: TsScan.Keyframe = TsScan.findKeyframe(window.packets, program, from) ?: keyframe
                    keyframe = last
                    while (true) {
                        last = TsScan.findKeyframe(window.packets, program, last.offset + TsScan.PACKET) ?: break
                        keyframe = last
                    }
                }
                break
            }
            // 一直读到流的末尾都没有关键帧：要的时刻在最后一个关键帧之后。往前退一段再找
            if (estimate == 0L || !window.atEnd) break
            estimate = packetAligned(estimate - backtrackBytes())
        }
        val found = keyframe ?: return null
        // 这一帧的数据要取全：读到下一帧的第一个包为止
        var end = found.endOffset
        while (end < 0 && window.packets.size - found.offset < MAX_FRAME) {
            val scanFrom = window.packets.size
            if (!window.extend()) break
            end = TsScan.nextFrameStart(window.packets, program.videoPid, scanFrom)
        }
        if (end < 0) end = window.packets.size

        val actualMs = found.ptsMs?.let { TsScan.elapsedMs(streamStartMs, it) }?.takeIf { it <= durationMs + SLACK_MS } ?: timeMs
        remember(actualMs, window.origin + found.offset)
        val slice = TsScan.slice(window.packets, program, found.offset, end)
        val frame = decode(slice) ?: return null
        return ThumbnailFrame(actualMs, frame.width, frame.height, frame.pixels)
    }

    override fun close() = Unit

    /**
     * 流里的一段，从 [estimate] 所在的那一块的开头起，按块往后延伸。
     * [packets] 是其中的整包部分，[origin] 是它第一个字节在流里的位置。
     */
    private inner class Window(estimate: Long) {
        private val base = estimate - estimate % block
        private val skip = ((TsScan.PACKET - base % TsScan.PACKET) % TsScan.PACKET).toInt()
        private var raw = ByteArray(0)
        val origin: Long = base + skip
        var packets = ByteArray(0)
            private set
        val atEnd: Boolean get() = base + raw.size >= reader.size

        /** 再读一块。已到流的末尾或读不到东西时返回 false。 */
        suspend fun extend(): Boolean {
            if (atEnd) return false
            val more = reader.read(base + raw.size, block)
            if (more.isEmpty()) return false
            fetched.addAndGet(more.size.toLong())
            raw += more
            val usable = raw.size - skip
            packets = if (usable < TsScan.PACKET) ByteArray(0) else raw.copyOfRange(skip, skip + usable - usable % TsScan.PACKET)
            return true
        }
    }

    /** 读流的开头，认出节目表与起始时间戳。只做一次。 */
    private suspend fun prepare(): TsScan.Program = prepareLock.withLock {
        program?.let { return it }
        if (durationMs <= 0 || reader.size < TsScan.PACKET * 16) throw SourceUnusableException("流太短或时长未知")
        if (reader.size % TsScan.PACKET != 0L) throw SourceUnusableException("长度不是 188 的整数倍，不是 TS")
        val head = Window(0).also { it.extend() }.packets
        if (!TsScan.looksLikeTs(head)) throw SourceUnusableException("开头不是 TS 包")
        val found = TsScan.findProgram(head) ?: throw SourceUnusableException("开头没有节目表")
        streamStartMs = TsScan.firstVideoPtsMs(head, found) ?: 0L
        remember(0L, 0L)
        remember(durationMs, reader.size)
        program = found
        found
    }

    /** [timeMs] 大约在流里的哪个字节：在已知的两个点之间按比例取。 */
    private fun offsetFor(timeMs: Long): Long = synchronized(knownLock) {
        val time = timeMs.coerceIn(0, durationMs)
        // 第一个不小于 time 的已知点
        val at = knownTimes.indexOfFirst { it >= time }
        if (knownTimes.isEmpty()) return@synchronized 0L
        if (at == -1) return@synchronized knownOffsets.last()
        if (knownTimes[at] == time || at == 0) return@synchronized if (knownTimes[at] == time) knownOffsets[at] else 0L
        val belowTime = knownTimes[at - 1]
        val belowOffset = knownOffsets[at - 1]
        val fraction = (time - belowTime).toDouble() / (knownTimes[at] - belowTime)
        (belowOffset + fraction * (knownOffsets[at] - belowOffset)).toLong()
    }

    /** 记下一个「时刻 → 字节位置」，表按时刻排着。 */
    private fun remember(timeMs: Long, offset: Long) = synchronized(knownLock) {
        val at = knownTimes.indexOfFirst { it >= timeMs }
        when {
            at == -1 -> { knownTimes += timeMs; knownOffsets += offset }
            knownTimes[at] == timeMs -> knownOffsets[at] = offset
            else -> { knownTimes.add(at, timeMs); knownOffsets.add(at, offset) }
        }
    }

    private suspend fun decode(slice: ByteArray): ThumbnailFrame? = decodeLock.withLock {
        withContext(Dispatchers.IO) {
            val file = Path(tempDirectory, "thumb-${serial.incrementAndGet()}.ts")
            try {
                SystemFileSystem.createDirectories(Path(tempDirectory))
                SystemFileSystem.sink(file).buffered().use { it.write(slice) }
                if (grabber.open(file.toString(), DECODE_TIMEOUT_MS)) grabber.grab() else null
            } finally {
                runCatching { SystemFileSystem.delete(file, mustExist = false) }
            }
        }
    }

    /** 往前退多远：大约一个半关键帧间隔的数据，至少一块。 */
    private fun backtrackBytes(): Long = maxOf(block.toLong(), reader.size * (LEAD_MS * 3) / durationMs)

    private fun packetAligned(offset: Long): Long =
        (offset - offset % TsScan.PACKET).coerceIn(0, (reader.size - TsScan.PACKET).coerceAtLeast(0))

    private companion object {
        const val MAX_SCAN = 6 * 1024 * 1024
        const val MAX_FRAME = 3 * 1024 * 1024
        const val RESCAN_PACKETS = 8
        const val BACKTRACKS = 4

        // 转码流约每 5 秒一个关键帧。往前让半个间隔再找，找到的关键帧平均正落在要的时刻附近
        const val LEAD_MS = 2_500L
        const val SLACK_MS = 10_000L
        const val DECODE_TIMEOUT_MS = 8_000L
    }
}

/**
 * 有索引的文件（MP4、MKV 的原画，或本机文件）：让解码器自己打开、自己跳到关键帧。
 * 它每次定位只读索引指给它的那一小段，不像 TS 那样要二分查找。
 *
 * [location] 是本机路径，或本机代理的地址（代理那一头以后台优先级读网盘）。
 */
class SeekingSource(
    private val location: String,
    private val grabber: FrameGrabber,
    override val description: String,
) : ThumbnailSource {
    override val maxParallel: Int = 1
    override val networkBytes: Long = -1

    private val lock = Mutex()
    private var opened = false

    override suspend fun frameNear(timeMs: Long): ThumbnailFrame? = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!opened) {
                // 直接从要的那个时刻打开，省掉「先解片头第一帧」这一步
                if (!grabber.open(location, startSeconds = timeMs / 1000.0)) throw SourceUnusableException("打不开视频")
                opened = true
                grabber.grab()
            } else {
                grabber.seekAndGrab(timeMs / 1000.0)
            }
        }
    }

    override fun close() = Unit
}
