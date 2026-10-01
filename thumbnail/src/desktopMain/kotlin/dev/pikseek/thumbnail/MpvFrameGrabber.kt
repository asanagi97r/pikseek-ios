package dev.pikseek.thumbnail

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * 一个不出画面、不出声音的 mpv 实例，专门解码取帧。与主播放器完全分开：
 * 它定位、它读文件都不动主播放器的任何东西。
 *
 * 不是线程安全的：一个实例同一时刻只由一个线程用。方法都会阻塞，调用方放在专门的线程上。
 *
 * 取到的帧已由 mpv 缩到 [frameWidth] 宽（高度按比例取偶数），软件解码：缩略图只有两百来像素宽，
 * 用不着显卡，也免得与主播放器抢硬解的资源。
 */
class MpvFrameGrabber(libraryDirectory: Path, private val frameWidth: Int = FrameGrabber.DEFAULT_WIDTH) : FrameGrabber {
    private val mpv = MpvLibrary.load(libraryDirectory)
    private val arena = Arena.ofShared()
    private val context: MemorySegment
    private var closed = false

    init {
        context = mpv.create.invoke() as MemorySegment
        check(context.address() != 0L) { "建不出 mpv 实例" }
        // 没有的选项（不同构建会少几个）跳过，不影响取帧
        OPTIONS.forEach { (name, value) -> option(name, value) }
        option("vf", "scale=w=$frameWidth:h=-2")
        val code = mpv.initialize.invoke(context) as Int
        check(code >= 0) { "mpv 初始化失败：${mpv.describeError(code)}" }
    }

    override var durationMs: Long = 0
        private set

    /**
     * 打开 [location]（本机路径或网址），停在第一帧上。打不开或 [timeoutMs] 内没出第一帧返回 false。
     * [startSeconds] 非空时直接从那里（最近的关键帧）打开。
     */
    override fun open(location: String, timeoutMs: Long, startSeconds: Double?): Boolean {
        check(!closed) { "已关闭" }
        durationMs = 0
        drainEvents()
        val started = if (startSeconds == null) {
            command("loadfile", location)
        } else {
            command("loadfile", location, "replace", "-1", "start=${"%.3f".format(java.util.Locale.ROOT, startSeconds)}")
        }
        if (!started) return false
        if (!awaitFrame(timeoutMs, newFile = true)) return false
        durationMs = property("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0
        return true
    }

    /** 眼下停着的那一帧。没有画面时返回 null。 */
    override fun grab(): ThumbnailFrame? {
        check(!closed) { "已关闭" }
        val timeMs = property("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        return screenshot(timeMs)
    }

    /**
     * 跳到 [seconds] 之前最近的关键帧并取那一帧。跳到关键帧而不是精确时刻：精确定位要从关键帧一路解到目标，
     * 网络流上慢得多，而缩略图差几秒无所谓，帧的真实时刻记在返回值里。
     */
    override fun seekAndGrab(seconds: Double, timeoutMs: Long): ThumbnailFrame? {
        check(!closed) { "已关闭" }
        drainEvents()
        if (!command("seek", "%.3f".format(java.util.Locale.ROOT, seconds), "absolute+keyframes")) return null
        if (!awaitFrame(timeoutMs, newFile = false)) return null
        return grab()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { mpv.terminateDestroy.invoke(context) }
        arena.close()
    }

    private fun option(name: String, value: String): Boolean = Arena.ofConfined().use { scratch ->
        (mpv.setOptionString.invoke(context, scratch.allocateFrom(name), scratch.allocateFrom(value)) as Int) >= 0
    }

    private fun property(name: String): String? = Arena.ofConfined().use { scratch ->
        val pointer = mpv.getPropertyString.invoke(context, scratch.allocateFrom(name)) as MemorySegment
        if (pointer.address() == 0L) return null
        try {
            pointer.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            mpv.free.invoke(pointer)
        }
    }

    private fun command(vararg arguments: String): Boolean = Arena.ofConfined().use { scratch ->
        val array = scratch.allocate(ADDRESS, arguments.size + 1L)
        arguments.forEachIndexed { index, argument -> array.setAtIndex(ADDRESS, index.toLong(), scratch.allocateFrom(argument)) }
        array.setAtIndex(ADDRESS, arguments.size.toLong(), MemorySegment.NULL)
        (mpv.command.invoke(context, array) as Int) >= 0
    }

    /** 把队列里积着的旧事件读掉，免得把上一次的「出帧了」当成这一次的。 */
    private fun drainEvents() {
        while (true) {
            val event = (mpv.waitEvent.invoke(context, 0.0) as MemorySegment).reinterpret(EVENT_SIZE)
            if (event.get(JAVA_INT, 0) == EVENT_NONE) return
        }
    }

    /**
     * 等到新的一帧出来（playback-restart）。文件结束、出错、超时都返回 false。
     *
     * [newFile] 为 true 是刚下了 loadfile：上一个文件被顶掉时也会发一个「文件结束」，那不是这一个的，
     * 要等到这一个文件的「开始」之后才认。
     */
    private fun awaitFrame(timeoutMs: Long, newFile: Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var started = !newFile
        while (System.nanoTime() < deadline) {
            val event = (mpv.waitEvent.invoke(context, 0.25) as MemorySegment).reinterpret(EVENT_SIZE)
            when (event.get(JAVA_INT, 0)) {
                EVENT_START_FILE -> started = true
                EVENT_PLAYBACK_RESTART -> if (started) return true
                EVENT_END_FILE -> if (started) {
                    val data = event.get(ADDRESS, 16)
                    val reason = if (data.address() == 0L) END_ERROR else data.reinterpret(8).get(JAVA_INT, 0)
                    // 只有一帧的小文件：解完这一帧就到了文件末尾。keep-open 让画面停在最后一帧，照样取得到；
                    // 出错或被别的文件顶掉才算失败
                    if (reason != END_EOF) return false
                }
                EVENT_SHUTDOWN -> return false
            }
        }
        return false
    }

    /**
     * `screenshot-raw video`：返回一张 bgr0 的位图。没有画面输出时 mpv 退到软件截图，取的是解码器交出来的那一帧。
     */
    private fun screenshot(timeMs: Long): ThumbnailFrame? = Arena.ofConfined().use { scratch ->
        val arguments = scratch.allocate(ADDRESS, 3)
        arguments.setAtIndex(ADDRESS, 0, scratch.allocateFrom("screenshot-raw"))
        arguments.setAtIndex(ADDRESS, 1, scratch.allocateFrom("video"))
        arguments.setAtIndex(ADDRESS, 2, MemorySegment.NULL)
        val node = scratch.allocate(NODE_SIZE)
        if ((mpv.commandRet.invoke(context, arguments, node) as Int) < 0) return null
        try {
            if (node.get(JAVA_INT, 8) != FORMAT_NODE_MAP) return null
            val list = node.get(ADDRESS, 0).reinterpret(24)
            val count = list.get(JAVA_INT, 0)
            val values = list.get(ADDRESS, 8).reinterpret(NODE_SIZE * count)
            val keys = list.get(ADDRESS, 16).reinterpret(ADDRESS.byteSize() * count)
            var width = 0
            var height = 0
            var stride = 0
            var data: MemorySegment? = null
            for (index in 0 until count) {
                val key = keys.getAtIndex(ADDRESS, index.toLong()).reinterpret(64).getString(0)
                val value = values.asSlice(NODE_SIZE * index, NODE_SIZE)
                when (value.get(JAVA_INT, 8)) {
                    FORMAT_INT64 -> when (key) {
                        "w" -> width = value.get(JAVA_LONG, 0).toInt()
                        "h" -> height = value.get(JAVA_LONG, 0).toInt()
                        "stride" -> stride = value.get(JAVA_LONG, 0).toInt()
                    }
                    FORMAT_BYTE_ARRAY -> if (key == "data") {
                        val array = value.get(ADDRESS, 0).reinterpret(16)
                        data = array.get(ADDRESS, 0).reinterpret(array.get(JAVA_LONG, 8))
                    }
                }
            }
            val bytes = data ?: return null
            if (width <= 0 || height <= 0 || stride < width * 4 || bytes.byteSize() < stride.toLong() * height) return null
            // bgr0：内存里依次是 B、G、R、填充，按小端读成 Int 正好是 0x00RRGGBB
            val pixels = IntArray(width * height)
            val row = bytes.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) {
                row.position(y * stride)
                row.asIntBuffer().get(pixels, y * width, width)
            }
            for (index in pixels.indices) pixels[index] = pixels[index] or OPAQUE
            rotated(ThumbnailFrame(timeMs, width, height, pixels))
        } finally {
            mpv.freeNodeContents.invoke(node)
        }
    }

    private fun rotated(frame: ThumbnailFrame): ThumbnailFrame {
        val rotate = property("video-params/rotate")?.toIntOrNull() ?: return frame
        val displayWidth = property("dwidth")?.toIntOrNull() ?: return frame
        val displayHeight = property("dheight")?.toIntOrNull() ?: return frame
        return frame.uprightFor(rotate, displayWidth, displayHeight)
    }

    companion object {
        const val DEFAULT_WIDTH = FrameGrabber.DEFAULT_WIDTH
        private const val OPAQUE = 0xFF000000.toInt()

        // mpv_event：event_id(int) error(int) reply_userdata(u64) data(void*)
        private const val EVENT_SIZE = 24L
        private const val EVENT_NONE = 0
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_START_FILE = 6
        private const val EVENT_END_FILE = 7
        private const val EVENT_PLAYBACK_RESTART = 21
        private const val END_EOF = 0
        private const val END_ERROR = 4

        // mpv_node：u(8 字节的联合) format(int)
        private const val NODE_SIZE = 16L
        private const val FORMAT_INT64 = 4
        private const val FORMAT_NODE_MAP = 8
        private const val FORMAT_BYTE_ARRAY = 9

        private val OPTIONS = listOf(
            // 不出画面、不出声音，停在帧上
            "vo" to "null",
            "ao" to "null",
            "aid" to "no",
            "sid" to "no",
            "pause" to "yes",
            "keep-open" to "always",
            "idle" to "yes",
            // 不读用户的 mpv 配置，不往别处写任何东西
            "config" to "no",
            "terminal" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osd-level" to "0",
            "resume-playback" to "no",
            "save-position-on-quit" to "no",
            // 不去同目录里找字幕、音轨与封面
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "cover-art-auto" to "no",
            "audio-display" to "no",
            // 软件解码、只求快：跳过去块滤波，缩放用快速算法
            "hwdec" to "no",
            "vd-lavc-threads" to "2",
            "vd-lavc-skiploopfilter" to "all",
            "vd-lavc-fast" to "yes",
            "sws-fast" to "yes",
            // 只落在关键帧上
            "hr-seek" to "no",
            // 往后只读够解一帧的量，不囤
            "cache" to "yes",
            "demuxer-max-bytes" to "${4 * 1024 * 1024}",
            "demuxer-max-back-bytes" to "0",
            "demuxer-readahead-secs" to "0",
            "cache-secs" to "1",
            "network-timeout" to "20",
        )
    }
}
