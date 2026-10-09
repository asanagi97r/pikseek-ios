package dev.pikseek.thumbnail

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 经程序自带的 libmpv 解声音：每次另建一个不出画面的 mpv 实例，音频输出换成 pcm（写文件，不等真实时间，
 * 能解多快就多快），写完读回来。实例用完即毁，与主播放器、取帧的实例都不相干。
 *
 * @param tempDirectory 中间的 PCM 文件放哪，读回来就删
 */
class MpvAudioDecoder(libraryDirectory: Path, private val tempDirectory: Path) : AudioDecoder {
    private val mpv = MpvLibrary.load(libraryDirectory)

    override fun decode(location: String, sampleRate: Int, startSeconds: Double?, lengthSeconds: Double?, timeoutMs: Long): ShortArray? {
        Files.createDirectories(tempDirectory)
        val output = tempDirectory.resolve("audio-${System.nanoTime()}-${serial.incrementAndGet()}.pcm")
        val context = mpv.create.invoke() as MemorySegment
        if (context.address() == 0L) return null
        var destroyed = false
        try {
            OPTIONS.forEach { (name, value) -> option(context, name, value) }
            option(context, "ao-pcm-file", output.toString())
            option(context, "audio-samplerate", sampleRate.toString())
            startSeconds?.let { option(context, "start", "%.3f".format(Locale.ROOT, it)) }
            lengthSeconds?.let { option(context, "length", "%.3f".format(Locale.ROOT, it)) }
            if ((mpv.initialize.invoke(context) as Int) < 0) return null
            if (!command(context, "loadfile", location)) return null
            if (!awaitEnd(context, timeoutMs)) return null
            // 先停掉实例，文件才写完、关上
            mpv.terminateDestroy.invoke(context)
            destroyed = true
            return readPcm(output)
        } finally {
            if (!destroyed) runCatching { mpv.terminateDestroy.invoke(context) }
            runCatching { Files.deleteIfExists(output) }
        }
    }

    private fun readPcm(file: Path): ShortArray? {
        if (!Files.isRegularFile(file)) return null
        val bytes = Files.readAllBytes(file)
        if (bytes.size < 2) return null
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    private fun option(context: MemorySegment, name: String, value: String): Boolean = Arena.ofConfined().use { scratch ->
        (mpv.setOptionString.invoke(context, scratch.allocateFrom(name), scratch.allocateFrom(value)) as Int) >= 0
    }

    private fun command(context: MemorySegment, vararg arguments: String): Boolean = Arena.ofConfined().use { scratch ->
        val array = scratch.allocate(ADDRESS, arguments.size + 1L)
        arguments.forEachIndexed { index, argument -> array.setAtIndex(ADDRESS, index.toLong(), scratch.allocateFrom(argument)) }
        array.setAtIndex(ADDRESS, arguments.size.toLong(), MemorySegment.NULL)
        (mpv.command.invoke(context, array) as Int) >= 0
    }

    /** 等到这个文件放完。正常放完返回 true；出错、被顶掉、超时返回 false。 */
    private fun awaitEnd(context: MemorySegment, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var started = false
        while (System.nanoTime() < deadline) {
            val event = (mpv.waitEvent.invoke(context, 0.25) as MemorySegment).reinterpret(EVENT_SIZE)
            when (event.get(JAVA_INT, 0)) {
                EVENT_START_FILE -> started = true
                EVENT_END_FILE -> if (started) {
                    val data = event.get(ADDRESS, 16)
                    val reason = if (data.address() == 0L) END_ERROR else data.reinterpret(8).get(JAVA_INT, 0)
                    return reason == END_EOF
                }
                EVENT_SHUTDOWN -> return false
            }
        }
        return false
    }

    private companion object {
        val serial = AtomicLong()

        // mpv_event：event_id(int) error(int) reply_userdata(u64) data(void*)
        const val EVENT_SIZE = 24L
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val END_EOF = 0
        const val END_ERROR = 4

        val OPTIONS = listOf(
            // 不出画面、不解视频，声音写成文件：单声道、16 位、不带 WAV 头
            "vo" to "null",
            "vid" to "no",
            "sid" to "no",
            "ao" to "pcm",
            "ao-pcm-waveheader" to "no",
            "audio-format" to "s16",
            "audio-channels" to "mono",
            // 放完停在空闲里等我们收尾，不自己退出（设 no 的话还没 loadfile 它就退了）
            "idle" to "yes",
            "keep-open" to "no",
            // 不读用户的 mpv 配置，不往别处写任何东西
            "config" to "no",
            "terminal" to "no",
            "input-default-bindings" to "no",
            "resume-playback" to "no",
            "save-position-on-quit" to "no",
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "cover-art-auto" to "no",
            "audio-display" to "no",
            // 外挂的音量、均衡一概不要：比对的是原样的声音
            "replaygain" to "no",
            "volume" to "100",
            "network-timeout" to "20",
        )
    }
}
