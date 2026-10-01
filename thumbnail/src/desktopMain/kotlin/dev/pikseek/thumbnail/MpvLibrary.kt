package dev.pikseek.thumbnail

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * libmpv 的客户端接口，经 JDK 的 FFM 直调。只绑缩略图用得上的那十来个函数。
 *
 * 用的是程序自带的那份 libmpv-2.dll（与主播放器同一个文件）。同一个进程里可以有多个 mpv 实例，互不相干：
 * 主播放器的实例归 MediaMP 管，这里另建的实例没有画面输出、没有声音，只解码取帧。
 *
 * 加载时带 LOAD_WITH_ALTERED_SEARCH_PATH：它依赖的 FFmpeg 等几十个 DLL 就在同一个目录里，
 * 这样系统从那个目录找，不必改进程的 DLL 搜索路径。
 */
class MpvLibrary private constructor(private val module: MemorySegment, private val getProcAddress: MethodHandle) {
    private val linker = Linker.nativeLinker()

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle = Arena.ofConfined().use { arena ->
        val address = getProcAddress.invoke(module, arena.allocateFrom(name)) as MemorySegment
        check(address.address() != 0L) { "libmpv 里没有 $name" }
        linker.downcallHandle(address, descriptor)
    }

    val create = function("mpv_create", FunctionDescriptor.of(ADDRESS))
    val setOptionString = function("mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    val initialize = function("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val command = function("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val commandRet = function("mpv_command_ret", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    val waitEvent = function("mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE))
    val freeNodeContents = function("mpv_free_node_contents", FunctionDescriptor.ofVoid(ADDRESS))
    val terminateDestroy = function("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    val getPropertyString = function("mpv_get_property_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
    val setPropertyString = function("mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    val free = function("mpv_free", FunctionDescriptor.ofVoid(ADDRESS))
    val errorString = function("mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    val clientApiVersion = function("mpv_client_api_version", FunctionDescriptor.of(JAVA_LONG))

    fun describeError(code: Int): String = runCatching {
        (errorString.invoke(code) as MemorySegment).reinterpret(256).getString(0)
    }.getOrDefault("错误 $code")

    companion object {
        private const val LOAD_WITH_ALTERED_SEARCH_PATH = 0x8
        private val loaded = HashMap<Path, MpvLibrary>()

        /** 从 [directory] 加载 libmpv。同一个目录只加载一次。加载不了抛异常。 */
        @Synchronized
        fun load(directory: Path): MpvLibrary {
            val key = directory.toAbsolutePath().normalize()
            loaded[key]?.let { return it }
            val dll = key.resolve("libmpv-2.dll")
            check(Files.isRegularFile(dll)) { "找不到 libmpv：$dll" }
            val linker = Linker.nativeLinker()
            val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", Arena.global())
            val loadLibraryEx = linker.downcallHandle(
                kernel32.find("LoadLibraryExW").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
            )
            val getProcAddress = linker.downcallHandle(
                kernel32.find("GetProcAddress").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            )
            val module = Arena.ofConfined().use { arena ->
                loadLibraryEx.invoke(
                    arena.allocateFrom(dll.toString(), StandardCharsets.UTF_16LE),
                    MemorySegment.NULL,
                    LOAD_WITH_ALTERED_SEARCH_PATH,
                ) as MemorySegment
            }
            check(module.address() != 0L) { "加载 libmpv 失败：$dll" }
            return MpvLibrary(module, getProcAddress).also { loaded[key] = it }
        }
    }
}
