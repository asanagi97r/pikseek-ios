package dev.pikseek.thumbnail

/**
 * 读 MPEG-TS 的最小一套：认出视频在哪个 PID、找关键帧、读时间戳、把一个关键帧单独截出来。
 *
 * PikPak 的转码流是 MPEG-TS，没有索引，播放器要在里面跳到某个时刻只能按时间戳二分查找，一次十来处跳读。
 * 缩略图不走那条路：按码率估一个字节位置，从那里往后找第一个关键帧，只把这一帧的包截下来交给解码器，
 * 当成一个只有一帧的小文件从头读。TS 的每个包自成一体，任意包边界起都能接着解，所以截得出来。
 *
 * 传进来的数据都要从包边界开始（流里偏移是 188 的整数倍）。
 */
object TsScan {
    const val PACKET = 188
    private const val SYNC = 0x47
    private const val PID_PAT = 0

    // PMT 里的流类型
    private const val TYPE_MPEG1 = 0x01
    private const val TYPE_MPEG2 = 0x02
    private const val TYPE_MPEG4 = 0x10
    const val TYPE_H264 = 0x1B
    const val TYPE_HEVC = 0x24
    private val VIDEO_TYPES = setOf(TYPE_MPEG1, TYPE_MPEG2, TYPE_MPEG4, TYPE_H264, TYPE_HEVC)

    // 关键帧的参数集可能排在分隔符与补充信息之后，往后多看几个包
    private const val KEYFRAME_PROBE_PACKETS = 6

    /** 节目表：视频的 PID 与编码，以及 PAT、PMT 两个包的原样拷贝，截出来的小文件要带上它们。 */
    class Program(val videoPid: Int, val streamType: Int, val pat: ByteArray, val pmt: ByteArray)

    /**
     * 一个关键帧。[offset] 是它第一个包相对传入数据的偏移；[endOffset] 是下一帧第一个包的偏移，
     * 这一帧的数据到那里为止，传入的数据里还没出现下一帧时为 -1（要再读一些）。
     */
    class Keyframe(val offset: Int, val ptsMs: Long?, val endOffset: Int)

    /** [data] 像不像 TS：开头连着几个包都以 0x47 起头。 */
    fun looksLikeTs(data: ByteArray): Boolean {
        if (data.size < PACKET * 3) return false
        return (0 until 3).all { data[it * PACKET].toInt() and 0xFF == SYNC }
    }

    /** 在 [data]（流的开头）里读 PAT 与 PMT。读不到返回 null。 */
    fun findProgram(data: ByteArray): Program? {
        var pat: ByteArray? = null
        var pmtPid = -1
        var offset = 0
        while (offset + PACKET <= data.size) {
            if (data[offset].toInt() and 0xFF != SYNC) return null
            val pid = pidAt(data, offset)
            if (hasPayloadStart(data, offset)) {
                val payload = payloadStart(data, offset)
                if (pid == PID_PAT && pat == null && payload >= 0) {
                    pmtPid = parsePat(data, payload, offset + PACKET)
                    if (pmtPid >= 0) pat = data.copyOfRange(offset, offset + PACKET)
                } else if (pid == pmtPid && pat != null && payload >= 0) {
                    val stream = parsePmt(data, payload, offset + PACKET)
                    if (stream != null) {
                        return Program(stream.first, stream.second, pat, data.copyOfRange(offset, offset + PACKET))
                    }
                }
            }
            offset += PACKET
        }
        return null
    }

    /**
     * [from] 起第一个视频关键帧。没有时返回 null。
     *
     * 认关键帧先看适配域里的随机访问标志（转码器一般都打），没有标志时看这一帧开头的 NAL 单元：
     * H.264 的 IDR 或序列参数集，HEVC 的 IRAP 或参数集。
     */
    fun findKeyframe(data: ByteArray, program: Program, from: Int = 0): Keyframe? {
        var offset = from - from % PACKET
        while (offset + PACKET <= data.size) {
            if (data[offset].toInt() and 0xFF != SYNC) return null
            if (pidAt(data, offset) == program.videoPid && hasPayloadStart(data, offset) && isKeyframeStart(data, offset, program)) {
                return Keyframe(offset, ptsOf(data, offset), nextFrameStart(data, program.videoPid, offset + PACKET))
            }
            offset += PACKET
        }
        return null
    }

    /** [from] 起下一帧第一个包的偏移，没有为 -1。用来在多读了一些数据之后补上 [Keyframe.endOffset]。 */
    fun nextFrameStart(data: ByteArray, videoPid: Int, from: Int): Int {
        var offset = from
        while (offset + PACKET <= data.size) {
            if (data[offset].toInt() and 0xFF != SYNC) return -1
            if (pidAt(data, offset) == videoPid && hasPayloadStart(data, offset)) return offset
            offset += PACKET
        }
        return -1
    }

    /** 流里第一个带时间戳的视频帧的时间戳（毫秒，流自己的时钟）。 */
    fun firstVideoPtsMs(data: ByteArray, program: Program): Long? {
        var offset = 0
        while (offset + PACKET <= data.size) {
            if (data[offset].toInt() and 0xFF != SYNC) return null
            if (pidAt(data, offset) == program.videoPid && hasPayloadStart(data, offset)) {
                ptsOf(data, offset)?.let { return it }
            }
            offset += PACKET
        }
        return null
    }

    /** 从流的起点到 [ptsMs] 过了多久。时间戳是 33 位的，约 26.5 小时回绕一次。 */
    fun elapsedMs(streamStartMs: Long, ptsMs: Long): Long {
        val wrap = (1L shl 33) / 90
        return ((ptsMs - streamStartMs) % wrap + wrap) % wrap
    }

    /**
     * 把 [data] 里 [start] 到 [end] 之间的视频包单独截成一个小 TS：前面放上 PAT 与 PMT，
     * 解复用器一上来就知道视频在哪个 PID、是什么编码。音频与其余 PID 的包不要。
     */
    fun slice(data: ByteArray, program: Program, start: Int, end: Int): ByteArray {
        val out = ByteSink(program.pat.size + program.pmt.size + (end - start))
        out.write(program.pat)
        out.write(program.pmt)
        var offset = start
        while (offset + PACKET <= end) {
            if (pidAt(data, offset) == program.videoPid) out.write(data, offset, PACKET)
            offset += PACKET
        }
        return out.toByteArray()
    }

    private fun pidAt(data: ByteArray, packet: Int): Int =
        ((data[packet + 1].toInt() and 0x1F) shl 8) or (data[packet + 2].toInt() and 0xFF)

    private fun hasPayloadStart(data: ByteArray, packet: Int): Boolean = data[packet + 1].toInt() and 0x40 != 0

    /** 这个包里负载的起点（绝对偏移）；没有负载时为 -1。 */
    private fun payloadStart(data: ByteArray, packet: Int): Int {
        val control = (data[packet + 3].toInt() shr 4) and 0x3
        if (control and 0x1 == 0) return -1
        if (control and 0x2 == 0) return packet + 4
        val start = packet + 5 + (data[packet + 4].toInt() and 0xFF)
        return if (start < packet + PACKET) start else -1
    }

    private fun randomAccess(data: ByteArray, packet: Int): Boolean {
        val control = (data[packet + 3].toInt() shr 4) and 0x3
        if (control and 0x2 == 0) return false
        val length = data[packet + 4].toInt() and 0xFF
        return length > 0 && data[packet + 5].toInt() and 0x40 != 0
    }

    /** PAT 里第一个节目的 PMT 所在 PID；读不出为 -1。 */
    private fun parsePat(data: ByteArray, payload: Int, limit: Int): Int {
        var at = payload + 1 + (data[payload].toInt() and 0xFF) // 跳过 pointer_field
        if (at + 8 > limit || data[at].toInt() and 0xFF != 0x00) return -1
        val sectionLength = ((data[at + 1].toInt() and 0x0F) shl 8) or (data[at + 2].toInt() and 0xFF)
        val end = minOf(at + 3 + sectionLength - 4, limit)
        at += 8
        while (at + 4 <= end) {
            val program = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
            val pid = ((data[at + 2].toInt() and 0x1F) shl 8) or (data[at + 3].toInt() and 0xFF)
            if (program != 0) return pid
            at += 4
        }
        return -1
    }

    /** PMT 里第一条视频流：PID 与流类型。 */
    private fun parsePmt(data: ByteArray, payload: Int, limit: Int): Pair<Int, Int>? {
        var at = payload + 1 + (data[payload].toInt() and 0xFF)
        if (at + 12 > limit || data[at].toInt() and 0xFF != 0x02) return null
        val sectionLength = ((data[at + 1].toInt() and 0x0F) shl 8) or (data[at + 2].toInt() and 0xFF)
        val end = minOf(at + 3 + sectionLength - 4, limit)
        val programInfoLength = ((data[at + 10].toInt() and 0x0F) shl 8) or (data[at + 11].toInt() and 0xFF)
        at += 12 + programInfoLength
        while (at + 5 <= end) {
            val type = data[at].toInt() and 0xFF
            val pid = ((data[at + 1].toInt() and 0x1F) shl 8) or (data[at + 2].toInt() and 0xFF)
            val infoLength = ((data[at + 3].toInt() and 0x0F) shl 8) or (data[at + 4].toInt() and 0xFF)
            if (type in VIDEO_TYPES) return pid to type
            at += 5 + infoLength
        }
        return null
    }

    /** PES 头里的显示时间戳，毫秒。这个包不是 PES 的开头或没带时间戳时为 null。 */
    private fun ptsOf(data: ByteArray, packet: Int): Long? {
        val at = payloadStart(data, packet)
        if (at < 0 || at + 14 > packet + PACKET) return null
        val isPes = data[at].toInt() == 0 && data[at + 1].toInt() == 0 && data[at + 2].toInt() == 1
        if (!isPes || data[at + 7].toInt() and 0x80 == 0) return null
        val ticks = ((data[at + 9].toLong() shr 1) and 0x07) shl 30 or
            ((data[at + 10].toLong() and 0xFF) shl 22) or
            (((data[at + 11].toLong() and 0xFF) shr 1) shl 15) or
            ((data[at + 12].toLong() and 0xFF) shl 7) or
            ((data[at + 13].toLong() and 0xFF) shr 1)
        return ticks / 90
    }

    private fun isKeyframeStart(data: ByteArray, packet: Int, program: Program): Boolean {
        if (randomAccess(data, packet)) return true
        if (program.streamType != TYPE_H264 && program.streamType != TYPE_HEVC) return false
        // 把这一帧开头几个包的负载接起来，在里面找 NAL 单元的起始码
        val head = ByteSink(PACKET * KEYFRAME_PROBE_PACKETS)
        var offset = packet
        var taken = 0
        while (offset + PACKET <= data.size && taken < KEYFRAME_PROBE_PACKETS) {
            if (pidAt(data, offset) == program.videoPid) {
                if (taken > 0 && hasPayloadStart(data, offset)) break
                var start = payloadStart(data, offset)
                if (start >= 0) {
                    // 第一个包的负载以 PES 头开始，跳过它
                    if (taken == 0 && start + 9 <= offset + PACKET) start += 9 + (data[start + 8].toInt() and 0xFF)
                    if (start < offset + PACKET) head.write(data, start, offset + PACKET - start)
                }
                taken++
            }
            offset += PACKET
        }
        val bytes = head.toByteArray()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0 && bytes[index + 2].toInt() == 1) {
                val header = bytes[index + 3].toInt() and 0xFF
                if (program.streamType == TYPE_H264) {
                    when (header and 0x1F) {
                        5, 7 -> return true // IDR、序列参数集
                        1 -> return false // 普通帧的片
                    }
                } else {
                    when ((header shr 1) and 0x3F) {
                        in 16..23, in 32..34 -> return true // IRAP、参数集
                        in 0..9 -> return false
                    }
                }
                index += 3
            } else {
                index++
            }
        }
        return false
    }
}

/** 往后追加字节的缓冲。 */
private class ByteSink(capacity: Int) {
    private var buffer = ByteArray(capacity.coerceAtLeast(16))
    private var size = 0

    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
