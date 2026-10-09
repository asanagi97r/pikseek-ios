package dev.piko.desktop

import dev.piko.desktop.winrt.ShellReveal
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsLinkAssociation
import dev.piko.desktop.winrt.WindowsToast
import dev.piko.ui.platform.LinkAssociationState
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 打好的包里跑一段依赖真实环境的操作，写出结果后退出。这些行为只有由真的启动器、在裁剪过的运行时里
 * 跑起来才测得出，JVM 单测测不到。
 *
 * 带 `-Dpikseek.selftest=<名字>` 启动（jpackage 的启动器不认命令行里的 -D，经环境变量
 * `JAVA_TOOL_OPTIONS` 传），结果逐行追加到 `-Dpikseek.selftest.out` 指的文件（启动器是窗口程序，
 * 标准输出接不出来），退出码 0 为通过。在单实例锁之前处理：开着的 PikSeek 不会把它当成后来者转交走。
 *
 * PikSeek 自己加的三项：`security`（认证存储、白名单、遥测检查）、`preview`（时间轴缩略图）与 `marks`（进度条分段：解声音、场景分点），见 PikSeekSelfTest。
 */
internal const val SELF_TEST_PROPERTY = "pikseek.selftest"
private const val SELF_TEST_OUT_PROPERTY = "pikseek.selftest.out"
private const val SELF_TEST_PATH_ENV = "PIKSEEK_SELFTEST_PATH"

internal fun runSelfTest(name: String): Int {
    val out = System.getProperty(SELF_TEST_OUT_PROPERTY)?.let(::File)
    fun report(line: String) {
        println(line)
        out?.appendText("$line\n")
    }
    val passed = runCatching {
        when (name) {
            // 设为默认打开方式，读回来应当是 Piko。Windows 只写登记、不打开系统设置：全新的 runner 没有 UserChoice，
            // 登记当场生效
            "link-register" -> runBlocking {
                val registered = when {
                    WinRTSupport.isWindows -> WindowsLinkAssociation.writeAndNotify()
                    isMacOs -> MacLinkAssociation.register()
                    isLinux -> LinuxLinkAssociation.register()
                    else -> false
                }
                val state = currentLinkState()
                report("registered=$registered state=$state")
                registered && state == LinkAssociationState.Default
            }
            "link-unregister" -> runBlocking {
                val removed = when {
                    WinRTSupport.isWindows -> WindowsLinkAssociation.unregister()
                    isLinux -> LinuxLinkAssociation.unregister()
                    else -> false
                }
                val state = currentLinkState()
                report("unregistered=$removed state=$state")
                removed && state == LinkAssociationState.NotDefault
            }
            // 在资源管理器里选中环境变量 PIKO_SELFTEST_PATH 指的文件，与传输页「打开所在文件夹」同一个调用。
            // 路径要带空格才验得到那个问题，经 JAVA_TOOL_OPTIONS 传会被空格拆开，所以走环境变量。
            // 这里只看接口答成功；窗口是否真的开在那个文件夹、选中了那个文件，由冒烟脚本经 Shell.Application 查
            // Linux 上看 org.freedesktop.FileManager1 答没答成功，要有实现了它的文件管理器在跑
            "reveal" -> {
                val path = System.getenv(SELF_TEST_PATH_ENV).orEmpty()
                val selected = File(path).isFile && when {
                    WinRTSupport.isWindows -> {
                        WindowsToast.initializeThread()
                        ShellReveal.select(File(path).absolutePath)
                    }
                    isLinux -> LinuxDesktop.revealNow(File(path))
                    else -> false
                }
                report("selected=$selected")
                selected
            }
            // 开窗放 PIKO_SELFTEST_PATH 指的本机视频，见 playbackSelfTest。PIKO_SELFTEST_HOLD 为放通之后窗口再留的秒数
            "play" -> playbackSelfTest(
                File(System.getenv(SELF_TEST_PATH_ENV).orEmpty()),
                holdMillis = (System.getenv("PIKSEEK_SELFTEST_HOLD")?.toLongOrNull() ?: 0L) * 1000,
                report = ::report,
            )
            // 系统接没接下通知。Linux 上要有通知服务（org.freedesktop.Notifications）在跑
            "notify" -> {
                val shown = when {
                    isLinux -> LinuxDesktop.showNotification("PikSeek 自检", "这是一条测试通知。")
                    isMacOs -> MacOs.showNotification("PikSeek 自检", "这是一条测试通知。")
                    else -> WinRTSupport.showNotification("PikSeek 自检", "这是一条测试通知。")
                }
                report("shown=$shown")
                shown
            }
            "security" -> dev.pikseek.desktop.PikSeekSelfTest.security(::report)
            "preview" -> dev.pikseek.desktop.PikSeekSelfTest.preview(File(System.getenv(SELF_TEST_PATH_ENV).orEmpty()), ::report)
            "marks" -> dev.pikseek.desktop.PikSeekSelfTest.marks(File(System.getenv(SELF_TEST_PATH_ENV).orEmpty()), ::report)
            else -> {
                report("unknown self test: $name")
                false
            }
        }
    }.getOrElse {
        report("self test crashed: ${it.stackTraceToString()}")
        false
    }
    report(if (passed) "PASS" else "FAIL")
    return if (passed) 0 else 1
}

private suspend fun currentLinkState(): LinkAssociationState? = when {
    WinRTSupport.isWindows -> WindowsLinkAssociation.state()
    isMacOs -> MacLinkAssociation.state()
    isLinux -> LinuxLinkAssociation.state()
    else -> null
}
