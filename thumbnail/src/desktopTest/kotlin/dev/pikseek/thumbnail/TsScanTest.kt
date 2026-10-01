package dev.pikseek.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsScanTest {
    private val stream = SyntheticTs(gopCount = 6)

    @Test
    fun recognisesTheStreamAndItsVideoTrack() {
        assertEquals(0L, stream.bytes.size.toLong() % TsScan.PACKET)
        assertTrue(TsScan.looksLikeTs(stream.bytes))
        assertFalse(TsScan.looksLikeTs(ByteArray(4096) { 1 }))
        val program = assertNotNull(TsScan.findProgram(stream.bytes.copyOf(TsScan.PACKET * 348)))
        assertEquals(SyntheticTs.VIDEO_PID, program.videoPid)
        assertEquals(TsScan.TYPE_H264, program.streamType)
        assertEquals(TsScan.PACKET, program.pat.size)
        assertEquals(TsScan.PACKET, program.pmt.size)
        assertEquals(stream.startPtsMs, TsScan.firstVideoPtsMs(stream.bytes, program))
    }

    @Test
    fun findsEveryKeyframeWithItsTimestampAndSkipsOrdinaryFrames() {
        val program = TsScan.findProgram(stream.bytes)!!
        var from = 0
        val found = ArrayList<TsScan.Keyframe>()
        while (true) {
            val keyframe = TsScan.findKeyframe(stream.bytes, program, from) ?: break
            found += keyframe
            from = keyframe.offset + TsScan.PACKET
        }
        // 每段 25 帧里只有第一帧是关键帧，其余 24 帧一个也不能认成关键帧
        assertEquals(stream.keyframeOffsets, found.map { it.offset.toLong() })
        found.forEachIndexed { index, keyframe ->
            assertEquals(index * stream.gopMs, TsScan.elapsedMs(stream.startPtsMs, keyframe.ptsMs!!))
            // 关键帧的数据到下一帧的第一个包为止
            assertTrue(keyframe.endOffset > keyframe.offset)
            assertEquals(0, keyframe.endOffset % TsScan.PACKET)
        }
    }

    @Test
    fun keyframesAreStillFoundWithoutTheRandomAccessFlag() {
        // 抹掉所有包的随机访问标志：这时只能靠帧开头的 NAL 单元来认
        val bytes = stream.bytes.copyOf()
        var offset = 0
        while (offset < bytes.size) {
            val hasAdaptation = bytes[offset + 3].toInt() and 0x20 != 0
            if (hasAdaptation && bytes[offset + 4].toInt() != 0) bytes[offset + 5] = (bytes[offset + 5].toInt() and 0x40.inv()).toByte()
            offset += TsScan.PACKET
        }
        val program = TsScan.findProgram(bytes)!!
        val first = assertNotNull(TsScan.findKeyframe(bytes, program))
        assertEquals(stream.keyframeOffsets[0], first.offset.toLong())
        val second = assertNotNull(TsScan.findKeyframe(bytes, program, first.offset + TsScan.PACKET))
        assertEquals(stream.keyframeOffsets[1], second.offset.toLong())
    }

    @Test
    fun sliceCarriesTablesAndOnlyVideoPackets() {
        val program = TsScan.findProgram(stream.bytes)!!
        val keyframe = TsScan.findKeyframe(stream.bytes, program, stream.keyframeOffsets[2].toInt())!!
        val slice = TsScan.slice(stream.bytes, program, keyframe.offset, keyframe.endOffset)
        assertEquals(0, slice.size % TsScan.PACKET)
        // 开头是 PAT、PMT，之后全是视频 PID 的包
        assertTrue(slice.copyOfRange(0, TsScan.PACKET).contentEquals(program.pat))
        assertTrue(slice.copyOfRange(TsScan.PACKET, 2 * TsScan.PACKET).contentEquals(program.pmt))
        var offset = 2 * TsScan.PACKET
        while (offset < slice.size) {
            val pid = ((slice[offset + 1].toInt() and 0x1F) shl 8) or (slice[offset + 2].toInt() and 0xFF)
            assertEquals(SyntheticTs.VIDEO_PID, pid)
            offset += TsScan.PACKET
        }
        // 只有这一帧：比整段小得多
        assertTrue(slice.size < stream.keyframeOffsets[3] - stream.keyframeOffsets[2])
    }

    @Test
    fun timestampWrapIsHandled() {
        val wrap = (1L shl 33) / 90
        assertEquals(5_000, TsScan.elapsedMs(wrap - 2_000, 3_000))
        assertEquals(0, TsScan.elapsedMs(1_234, 1_234))
    }

    @Test
    fun garbageIsRejectedNotMisread() {
        assertNull(TsScan.findProgram(ByteArray(TsScan.PACKET * 20)))
        val program = TsScan.findProgram(stream.bytes)!!
        assertNull(TsScan.findKeyframe(ByteArray(TsScan.PACKET * 20), program))
    }
}
