package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import dev.pikseek.platform.AppPaths
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.JAVA_INT
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * 同一个数据目录只跑一个 PikSeek。磁力链接经协议唤起时每次都会新起一个进程，后来者把参数转交给
 * 已在运行的实例后退出；两个进程同时写设置与会话文件也会互相覆盖。
 *
 * 谁是主实例由数据目录里的文件锁决定，进程崩溃时系统释放锁，不会有残留的「已在运行」。
 * 两份放在不同文件夹的便携版各有各的数据目录，互不相干，可以同时开。
 *
 * 消息走本机回环上的一个随机端口，端口号与一段随机口令写在数据目录的 `instance.port` 里，后来者读它来连。
 * Piko 原本用 Unix domain socket，路径上限约 108 字节，便携版放得深一点就绑不上；回环端口没有这个限制。
 * 只监听 127.0.0.1，口令对不上的连接直接丢掉。
 */
class SingleInstance private constructor(
    // 只为持有：锁对象被回收时锁随之释放
    @Suppress("unused") private val lock: FileLock?,
    private val server: ServerSocket?,
    private val token: String,
) {
    /** 在后台线程上接收后来者的启动参数，每次连接回调一次 [onArgs]。 */
    fun listen(onArgs: (List<String>) -> Unit) {
        val server = server ?: return
        Thread(
            {
                while (!server.isClosed) {
                    val args = runCatching { server.accept().use { readArgs(it, token) } }.getOrNull() ?: continue
                    onArgs(args)
                }
            },
            "PikSeek-Single-Instance",
        ).apply { isDaemon = true; start() }
    }

    companion object {
        private const val TAG = "SingleInstance"
        private val directory: Path get() = AppPaths.dataRoot
        private val portFile: Path get() = directory.resolve("instance.port")

        /**
         * 成为主实例则返回它；已有实例在运行时把 [args] 转交过去并返回 null，调用方应直接退出。
         * 锁都拿不到（目录不可写之类）时也按主实例启动，宁可多开也不能打不开。
         */
        fun acquireOrForward(args: List<String>): SingleInstance? {
            val channel = runCatching {
                FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }.getOrElse {
                PikoLog.w(TAG, "无法创建实例锁，按独立实例启动", it)
                return SingleInstance(lock = null, server = null, token = "")
            }
            // 同一 JVM 里重复加锁抛 OverlappingFileLockException，别的进程持有时返回 null
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                channel.close()
                forward(args)
                return null
            }
            val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val server = runCatching {
                ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8) }
                    .also { Files.writeString(portFile, "${it.localPort}\n$token\n") }
            }.onFailure { PikoLog.w(TAG, "无法监听实例端口，后来的启动参数收不到", it) }.getOrNull()
            return SingleInstance(lock, server, token)
        }

        /**
         * 主实例刚拿到锁、还没写好端口文件时后来者就可能来读，所以读不到或连不上时短暂重试。
         * 转交前放开前台权限：Windows 只允许前台进程把别的窗口提到前面，此刻前台是刚被
         * 用户唤起的这个进程，主实例自己调 toFront 只会让任务栏图标闪烁。
         */
        private fun forward(args: List<String>) {
            allowAnyForeground()
            repeat(20) {
                val sent = runCatching {
                    val (port, token) = Files.readAllLines(portFile).let { it[0].trim().toInt() to it[1].trim() }
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                        writeArgs(socket, token, args)
                    }
                }.isSuccess
                if (sent) return
                Thread.sleep(100)
            }
            PikoLog.w(TAG, "已有实例在运行，但转交启动参数失败")
        }

        private fun writeArgs(socket: Socket, token: String, args: List<String>) {
            DataOutputStream(socket.getOutputStream()).run {
                writeUTF(token)
                val bytes = args.joinToString("\u0000").toByteArray(Charsets.UTF_8)
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
        }

        /** 口令不对或格式不对时返回 null，调用方丢掉这次连接。 */
        private fun readArgs(socket: Socket, token: String): List<String>? {
            socket.soTimeout = 2_000
            val input = DataInputStream(socket.getInputStream())
            if (input.readUTF() != token) return null
            val size = input.readInt()
            if (size < 0 || size > (1 shl 20)) return null
            val text = String(input.readNBytes(size), Charsets.UTF_8)
            return if (text.isEmpty()) emptyList() else text.split('\u0000')
        }

        private fun allowAnyForeground() {
            if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
            runCatching {
                val user32 = SymbolLookup.libraryLookup("user32", Arena.global())
                val handle = Linker.nativeLinker().downcallHandle(
                    user32.find("AllowSetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT),
                )
                handle.invokeWithArguments(ASFW_ANY)
            }
        }

        private const val ASFW_ANY = -1
    }
}
