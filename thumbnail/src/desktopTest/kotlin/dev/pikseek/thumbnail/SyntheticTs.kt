package dev.pikseek.thumbnail

import java.io.ByteArrayOutputStream

/**
 * 测试用的合成视频流：一段真的 H.264 包在真的 MPEG-TS 里，解码器解得出来。
 *
 * 开发机上没有 PikPak 账号，拿不到真的转码流；仓库里的样片又没有一个是 TS。所以自己造一条形状相同的：
 * 每 [gopMs] 一个关键帧，关键帧之间是「全部跳过」的 P 帧，PAT 与 PMT 只在流的开头出现一次，
 * 各段之间塞着数量不等的空包，码率因此不恒定。每个关键帧是一种纯色，颜色由它是第几个关键帧决定，
 * 于是「取到的这一帧是不是该时刻的画面」可以按颜色核对。
 *
 * H.264 这一侧用 I_PCM 宏块：像素原样写进码流，不需要变换与熵编码，一百来行就能写出合规的关键帧。
 */
class SyntheticTs(
    val gopCount: Int = 12,
    val gopMs: Long = 5_000,
    private val framesPerGop: Int = 25,
    private val widthMbs: Int = 20,
    private val heightMbs: Int = 11,
    /** 流的第一个时间戳，毫秒。真的转码流也不从 0 开始。 */
    val startPtsMs: Long = 10_000,
    /** 哪几个关键帧是全黑的，测黑帧重取。 */
    private val blackGops: Set<Int> = emptySet(),
) {
    val durationMs: Long = gopCount * gopMs
    val width: Int = widthMbs * 16
    val height: Int = heightMbs * 16

    /** 各关键帧第一个包在流里的字节偏移。 */
    val keyframeOffsets = ArrayList<Long>()
    val bytes: ByteArray = build()

    /** 第 [gop] 个关键帧的颜色，YCbCr。 */
    fun colorOf(gop: Int): Triple<Int, Int, Int> =
        if (gop in blackGops) Triple(16, 128, 128) else Triple(60 + gop * 13 % 150, 90 + gop * 37 % 80, 90 + gop * 59 % 80)

    /** 解码后该是什么颜色：BT.601、有限范围，标清片没有写明色彩空间时解码器按这个转。 */
    fun rgbOf(gop: Int): Triple<Int, Int, Int> {
        val (y, cb, cr) = colorOf(gop)
        val luma = 1.164 * (y - 16)
        fun clamp(value: Double) = value.toInt().coerceIn(0, 255)
        return Triple(
            clamp(luma + 1.596 * (cr - 128)),
            clamp(luma - 0.392 * (cb - 128) - 0.813 * (cr - 128)),
            clamp(luma + 2.017 * (cb - 128)),
        )
    }

    /** [timeMs] 落在第几个关键帧起的那一段里。 */
    fun gopAt(timeMs: Long): Int = (timeMs / gopMs).toInt().coerceIn(0, gopCount - 1)

    private fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        val mux = TsMuxer(out)
        mux.writeTables()
        val sps = H264.sps(widthMbs, heightMbs)
        val pps = H264.pps()
        val frameMs = gopMs / framesPerGop
        for (gop in 0 until gopCount) {
            keyframeOffsets += out.size().toLong()
            val (y, cb, cr) = colorOf(gop)
            val idr = H264.annexB(H264.aud(keyframe = true), sps, pps, H264.idr(widthMbs, heightMbs, gop, y, cb, cr))
            mux.writeFrame(idr, startPtsMs + gop * gopMs, keyframe = true)
            for (frame in 1 until framesPerGop) {
                val skip = H264.annexB(H264.aud(keyframe = false), H264.skippedP(widthMbs * heightMbs, frame % 16))
                mux.writeFrame(skip, startPtsMs + gop * gopMs + frame * frameMs, keyframe = false)
            }
            // 各段后面的填充不一样多：按平均码率估位置会有偏差
            mux.writeNullPackets(40 + gop * 97 % 400)
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var filled = 0

        fun bit(value: Int) {
            current = (current shl 1) or (value and 1)
            if (++filled == 8) {
                out.write(current)
                current = 0
                filled = 0
            }
        }

        fun bits(count: Int, value: Int) {
            for (index in count - 1 downTo 0) bit(value shr index)
        }

        /** 无符号指数哥伦布码。 */
        fun ue(value: Int) {
            val coded = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(coded)
            repeat(length - 1) { bit(0) }
            bits(length, coded)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun align() {
            while (filled != 0) bit(0)
        }

        fun bytes(data: ByteArray) {
            check(filled == 0)
            out.write(data)
        }

        /** rbsp_trailing_bits：一个 1，再补 0 到字节边界。 */
        fun finish(): ByteArray {
            bit(1)
            align()
            return out.toByteArray()
        }
    }

    private object H264 {
        fun sps(widthMbs: Int, heightMbs: Int): ByteArray = BitWriter().run {
            bits(8, 0x67) // nal_ref_idc 3，序列参数集
            bits(8, 66) // Baseline
            bits(8, 0xC0) // constraint_set0、1
            bits(8, 30) // level 3.0
            ue(0) // seq_parameter_set_id
            ue(0) // log2_max_frame_num_minus4：frame_num 占 4 位
            ue(2) // pic_order_cnt_type 2：输出顺序即解码顺序
            ue(1) // max_num_ref_frames
            bit(0) // gaps_in_frame_num_value_allowed_flag
            ue(widthMbs - 1)
            ue(heightMbs - 1)
            bit(1) // frame_mbs_only_flag
            bit(1) // direct_8x8_inference_flag
            bit(0) // frame_cropping_flag
            bit(0) // vui_parameters_present_flag
            finish()
        }

        fun pps(): ByteArray = BitWriter().run {
            bits(8, 0x68)
            ue(0) // pic_parameter_set_id
            ue(0) // seq_parameter_set_id
            bit(0) // entropy_coding_mode_flag：CAVLC
            bit(0) // bottom_field_pic_order_in_frame_present_flag
            ue(0) // num_slice_groups_minus1
            ue(0) // num_ref_idx_l0_default_active_minus1
            ue(0) // num_ref_idx_l1_default_active_minus1
            bit(0) // weighted_pred_flag
            bits(2, 0) // weighted_bipred_idc
            se(0) // pic_init_qp_minus26
            se(0) // pic_init_qs_minus26
            se(0) // chroma_qp_index_offset
            bit(1) // deblocking_filter_control_present_flag
            bit(0) // constrained_intra_pred_flag
            bit(0) // redundant_pic_cnt_present_flag
            finish()
        }

        /** 访问单元分隔符。TS 里的 H.264 每帧开头都要有。 */
        fun aud(keyframe: Boolean): ByteArray = byteArrayOf(0x09, if (keyframe) 0x10 else 0x30)

        /** 一个 IDR 帧：每个宏块都是 I_PCM，整帧一种颜色。 */
        fun idr(widthMbs: Int, heightMbs: Int, idrId: Int, y: Int, cb: Int, cr: Int): ByteArray = BitWriter().run {
            bits(8, 0x65) // nal_ref_idc 3，IDR 片
            ue(0) // first_mb_in_slice
            ue(7) // slice_type：I，整帧同类
            ue(0) // pic_parameter_set_id
            bits(4, 0) // frame_num
            ue(idrId and 0xFFFF) // idr_pic_id
            bit(0) // no_output_of_prior_pics_flag
            bit(0) // long_term_reference_flag
            se(0) // slice_qp_delta
            ue(1) // disable_deblocking_filter_idc：关掉去块滤波
            val samples = ByteArray(384)
            samples.fill(y.toByte(), 0, 256)
            samples.fill(cb.toByte(), 256, 320)
            samples.fill(cr.toByte(), 320, 384)
            repeat(widthMbs * heightMbs) {
                ue(25) // mb_type：I_PCM
                align() // pcm_alignment_zero_bit
                bytes(samples)
            }
            finish()
        }

        /** 一个 P 帧，所有宏块都跳过：画面与上一帧相同，只占十来个字节。 */
        fun skippedP(totalMbs: Int, frameNum: Int): ByteArray = BitWriter().run {
            bits(8, 0x41) // nal_ref_idc 2，非 IDR 片
            ue(0) // first_mb_in_slice
            ue(5) // slice_type：P，整帧同类
            ue(0) // pic_parameter_set_id
            bits(4, frameNum)
            bit(0) // num_ref_idx_active_override_flag
            bit(0) // ref_pic_list_modification_flag_l0
            bit(0) // adaptive_ref_pic_marking_mode_flag
            se(0) // slice_qp_delta
            ue(1) // disable_deblocking_filter_idc
            ue(totalMbs) // mb_skip_run
            finish()
        }

        /** 各 NAL 单元加上起始码，并做防竞争处理：数据里出现 00 00 0x 时插一个 03。 */
        fun annexB(vararg units: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (unit in units) {
                out.write(byteArrayOf(0, 0, 0, 1))
                var zeros = 0
                for (byte in unit) {
                    val value = byte.toInt() and 0xFF
                    if (zeros >= 2 && value <= 3) {
                        out.write(3)
                        zeros = 0
                    }
                    out.write(value)
                    zeros = if (value == 0) zeros + 1 else 0
                }
            }
            return out.toByteArray()
        }
    }

    private class TsMuxer(private val out: ByteArrayOutputStream) {
        private val counters = HashMap<Int, Int>()

        fun writeTables() {
            // PAT：节目 1 的 PMT 在 PMT_PID
            section(0, byteArrayOf(0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()), tableId = 0x00)
            // PMT：一条 H.264 视频流在 VIDEO_PID，时钟也在它上面
            section(
                PMT_PID,
                byteArrayOf(
                    0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
                    (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(),
                    0xF0.toByte(), 0x00,
                    0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0x00,
                ),
                tableId = 0x02,
            )
        }

        private fun section(pid: Int, body: ByteArray, tableId: Int) {
            val length = body.size + 4
            val section = ByteArrayOutputStream()
            section.write(tableId)
            section.write(0xB0 or (length shr 8))
            section.write(length and 0xFF)
            section.write(body)
            val crc = crc32(section.toByteArray())
            section.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
            val payload = byteArrayOf(0) + section.toByteArray() // pointer_field
            packet(pid, payloadStart = true, adaptation = null, payload = payload, offset = 0, length = payload.size)
        }

        fun writeFrame(elementary: ByteArray, ptsMs: Long, keyframe: Boolean) {
            val pts = ptsMs * 90
            val pes = ByteArrayOutputStream()
            pes.write(byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5))
            pes.write(
                byteArrayOf(
                    (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
                    (pts shr 22).toByte(),
                    (0x01 or ((pts shr 14).toInt() and 0xFE)).toByte(),
                    (pts shr 7).toByte(),
                    (0x01 or ((pts shl 1).toInt() and 0xFE)).toByte(),
                ),
            )
            pes.write(elementary)
            val data = pes.toByteArray()
            var offset = 0
            var first = true
            while (offset < data.size) {
                // 关键帧的第一个包带随机访问标志与时钟
                val adaptation = if (first && keyframe) pcrField(pts) else null
                val room = 184 - (adaptation?.let { it.size + 1 } ?: 0)
                val take = minOf(room, data.size - offset)
                packet(VIDEO_PID, payloadStart = first, adaptation = adaptation, payload = data, offset = offset, length = take)
                offset += take
                first = false
            }
        }

        fun writeNullPackets(count: Int) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = 0x1F
            packet[2] = 0xFF.toByte()
            packet[3] = 0x10
            repeat(count) { out.write(packet) }
        }

        /** 适配域的内容（不含长度字节）：随机访问标志加 PCR。 */
        private fun pcrField(pts: Long): ByteArray {
            val base = (pts - 9_000).coerceAtLeast(0)
            return byteArrayOf(
                0x50, // random_access_indicator、PCR_flag
                (base shr 25).toByte(),
                (base shr 17).toByte(),
                (base shr 9).toByte(),
                (base shr 1).toByte(),
                (((base and 1) shl 7) or 0x7E).toByte(),
                0,
            )
        }

        /** 写一个 188 字节的包。负载不够填满时在适配域里塞填充字节。 */
        private fun packet(pid: Int, payloadStart: Boolean, adaptation: ByteArray?, payload: ByteArray, offset: Int, length: Int) {
            val counter = counters.getOrDefault(pid, 0)
            counters[pid] = (counter + 1) and 0x0F
            var field = adaptation
            val used = (field?.let { it.size + 1 } ?: 0) + length
            if (used < 184) {
                val stuffing = 184 - used
                field = when {
                    field != null -> field + ByteArray(stuffing) { 0xFF.toByte() }
                    // 只差一个字节：适配域只有长度字节，长度为 0
                    stuffing == 1 -> ByteArray(0)
                    else -> byteArrayOf(0) + ByteArray(stuffing - 2) { 0xFF.toByte() }
                }
            }
            out.write(0x47)
            out.write((if (payloadStart) 0x40 else 0) or (pid shr 8))
            out.write(pid and 0xFF)
            out.write((if (field != null) 0x30 else 0x10) or counter)
            if (field != null) {
                out.write(field.size)
                out.write(field)
            }
            out.write(payload, offset, length)
        }

        /** MPEG-2 的 CRC32：多项式 0x04C11DB7，不反转。 */
        private fun crc32(data: ByteArray): Int {
            var crc = -1
            for (byte in data) {
                crc = crc xor (byte.toInt() shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }

    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
    }
}

/** 把内存里的一段字节当成网盘上的流来读，并数读了多少、同时有几路在读。 */
class MemoryRangeReader(private val data: ByteArray, override val blockSize: Int = 64 * 1024) : ByteRangeReader {
    override val size: Long get() = data.size.toLong()
    var reads = 0
        private set

    /** 没有对齐到块的读取次数。底下那一层按块取数据，不对齐就会多下一块。 */
    var misaligned = 0
        private set

    @Volatile
    var failing = false

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (failing) throw java.io.IOException("断网")
        reads++
        if (offset % blockSize != 0L || length != blockSize) misaligned++
        val from = offset.coerceIn(0, size).toInt()
        val to = (offset + length).coerceIn(0, size).toInt()
        return data.copyOfRange(from, to)
    }
}

/** 测试用的无损编码：PNG。正式程序用的是 Skia 的 WebP，在界面那一侧。 */
object PngSpriteCodec : SpriteCodec {
    override val extension: String = "png"

    override fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    override fun decode(bytes: ByteArray): DecodedSprite? {
        val image = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
        return DecodedSprite(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }
}
