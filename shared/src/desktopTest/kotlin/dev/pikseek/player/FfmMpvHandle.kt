package dev.pikseek.player

import dev.pikseek.thumbnail.MpvLibrary
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * 测试用的 [MpvHandle]：经 FFM 直调程序自带的 libmpv，不出画面、不出声音。
 * 与 iOS 上 Swift 写的那份实现同一套约定（属性按字符串订阅、事件用 mpv 的名字、end-file 带原因），
 * 拿它在桌面上把 [MpvPlaybackBackend] 对着真的 mpv 跑一遍。
 */
class FfmMpvHandle(libraryDirectory: Path) : MpvHandle {
    private val mpv = MpvLibrary.load(libraryDirectory)
    private val context: MemorySegment = mpv.create.invoke() as MemorySegment

    @Volatile
    private var listener: MpvListener? = null

    @Volatile
    private var closed = false
    private val pump: Thread

    init {
        check(context.address() != 0L) { "建不出 mpv 实例" }
        for ((name, value) in listOf("vo" to "null", "ao" to "null", "hwdec" to "no", "config" to "no", "terminal" to "no")) option(name, value)
        check((mpv.initialize.invoke(context) as Int) >= 0) { "mpv 初始化失败" }
        pump = thread(name = "mpv-events", isDaemon = true) { pumpEvents() }
    }

    override fun command(arguments: List<String>): Boolean = Arena.ofConfined().use { scratch ->
        val array = scratch.allocate(ADDRESS, arguments.size + 1L)
        arguments.forEachIndexed { index, argument -> array.setAtIndex(ADDRESS, index.toLong(), scratch.allocateFrom(argument)) }
        array.setAtIndex(ADDRESS, arguments.size.toLong(), MemorySegment.NULL)
        (mpv.command.invoke(context, array) as Int) >= 0
    }

    override fun setProperty(name: String, value: String): Boolean = Arena.ofConfined().use { scratch ->
        (mpv.setPropertyString.invoke(context, scratch.allocateFrom(name), scratch.allocateFrom(value)) as Int) >= 0
    }

    override fun getProperty(name: String): String? = Arena.ofConfined().use { scratch ->
        val pointer = mpv.getPropertyString.invoke(context, scratch.allocateFrom(name)) as MemorySegment
        if (pointer.address() == 0L) return null
        try {
            pointer.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            mpv.free.invoke(pointer)
        }
    }

    override fun observe(name: String) {
        Arena.ofConfined().use { scratch -> mpv.observeProperty.invoke(context, 0L, scratch.allocateFrom(name), FORMAT_STRING) }
    }

    override fun setListener(listener: MpvListener?) {
        this.listener = listener
    }

    override fun close() {
        if (closed) return
        closed = true
        pump.join(2_000)
        runCatching { mpv.terminateDestroy.invoke(context) }
    }

    private fun option(name: String, value: String) = Arena.ofConfined().use { scratch ->
        mpv.setOptionString.invoke(context, scratch.allocateFrom(name), scratch.allocateFrom(value))
    }

    private fun pumpEvents() {
        while (!closed) {
            val event = (mpv.waitEvent.invoke(context, 0.1) as MemorySegment).reinterpret(24)
            val data = event.get(ADDRESS, 16)
            when (event.get(JAVA_INT, 0)) {
                EVENT_NONE -> Unit
                EVENT_SHUTDOWN -> {
                    listener?.onEvent("shutdown", null)
                    return
                }
                EVENT_START_FILE -> listener?.onEvent("start-file", null)
                EVENT_FILE_LOADED -> listener?.onEvent("file-loaded", null)
                EVENT_SEEK -> listener?.onEvent("seek", null)
                EVENT_PLAYBACK_RESTART -> listener?.onEvent("playback-restart", null)
                EVENT_END_FILE -> {
                    // mpv_event_end_file：reason(int) error(int)
                    val end = data.reinterpret(8)
                    val detail = when (end.get(JAVA_INT, 0)) {
                        0 -> "eof"
                        2 -> "stop"
                        3 -> "quit"
                        5 -> "redirect"
                        else -> "error:" + mpv.describeError(end.get(JAVA_INT, 4))
                    }
                    listener?.onEvent("end-file", detail)
                }
                EVENT_PROPERTY_CHANGE -> {
                    // mpv_event_property：name(char*) format(int) data(void*)
                    val property = data.reinterpret(24)
                    val name = property.get(ADDRESS, 0).reinterpret(256).getString(0)
                    val value = if (property.get(JAVA_INT, 8) == FORMAT_STRING) {
                        property.get(ADDRESS, 16).reinterpret(8).get(ADDRESS, 0).reinterpret(Long.MAX_VALUE).getString(0)
                    } else {
                        null
                    }
                    listener?.onPropertyChanged(name, value)
                }
            }
        }
    }

    private companion object {
        const val FORMAT_STRING = 1
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22
    }
}
