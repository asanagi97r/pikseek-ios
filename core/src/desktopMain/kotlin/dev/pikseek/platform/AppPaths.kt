package dev.pikseek.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * PikSeek 在本机写文件的唯一去处。别处一律从这里取目录，不自己拼 user.home。
 *
 * 便携版把数据放在程序目录旁的 `data/` 里：整个文件夹拷到另一台电脑照样能用，也不往用户目录里留东西。
 * 登录会话由 DPAPI 按「当前 Windows 用户」加密，拷到别的电脑或别的用户下解不开，那边要重新登录一次，
 * 这是有意的。
 *
 * 取哪个目录，按顺序：
 * 1. 系统属性 `pikseek.data.dir` 或环境变量 `PIKSEEK_DATA_DIR`（测试与排查用）；
 * 2. 打包后的程序：程序目录下的 `data/`，要求可写；
 * 3. 程序目录不可写（例如放进了 Program Files）：`%LOCALAPPDATA%\PikSeek`；
 * 4. 开发时（gradle run，没有程序目录）：`~/.pikseek`。
 */
object AppPaths {
    const val APP_NAME = "PikSeek"

    /** 数据目录是怎么定下来的。安全页照实写。 */
    enum class Location(val label: String) {
        Portable("程序旁，便携"),
        Explicit("由 pikseek.data.dir 指定"),
        UserProfile("用户目录"),
    }

    @Volatile
    var location: Location = Location.UserProfile
        private set

    /** 数据落在程序目录旁（便携）。 */
    val isPortable: Boolean get() = location == Location.Portable

    val dataRoot: Path by lazy { resolveDataRoot() }

    /** 登录会话与账号列表的密文。 */
    val auth: Path get() = dir("auth")
    val logs: Path get() = dir("logs")
    val cache: Path get() = dir("cache")

    /** 时间轴缩略图的雪碧图与索引。 */
    val thumbnails: Path get() = dir("thumbnails")

    /** 进程自己的临时文件。启动时清空，不用系统的 %TEMP%。 */
    val temp: Path get() = dir("tmp")
    val settingsFile: Path get() = dataRoot.resolve("settings.properties")

    /** 打包后的程序目录（PikSeek.exe 所在），开发时为 null。 */
    val appRoot: Path? by lazy { findAppRoot() }

    fun dir(name: String): Path = dataRoot.resolve(name).also { Files.createDirectories(it) }

    /**
     * 在一切会写临时文件的代码之前调用：把 JVM 与 JNA 的临时目录指进数据目录，不用系统的 %TEMP%。
     */
    fun useTempDirectory() {
        val tmp = temp.toString()
        System.setProperty("java.io.tmpdir", tmp)
        System.setProperty("jna.tmpdir", tmp)
    }

    /**
     * 清掉上次运行留下的临时文件。拿到单实例锁之后才调：后来的进程不该删主实例正用着的。
     * 只删本目录下的东西，删不掉的跳过。
     */
    fun cleanTempDirectory() {
        runCatching {
            Files.newDirectoryStream(temp).use { entries -> entries.forEach { it.toFile().deleteRecursively() } }
        }
    }

    private fun resolveDataRoot(): Path {
        val explicit = System.getProperty("pikseek.data.dir") ?: System.getenv("PIKSEEK_DATA_DIR")
        if (!explicit.isNullOrBlank()) {
            location = Location.Explicit
            return usable(Path.of(explicit)) ?: error("数据目录不可写：$explicit")
        }

        appRoot?.let { root ->
            usable(root.resolve("data"))?.let {
                location = Location.Portable
                return it
            }
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            if (local != null) usable(Path.of(local, APP_NAME))?.let { return it }
        }
        return usable(Path.of(System.getProperty("user.home"), ".pikseek"))
            ?: error("找不到可写的数据目录")
    }

    /** 能建出来、能写进去才算。只读介质或没权限时返回 null。 */
    private fun usable(directory: Path): Path? = runCatching {
        Files.createDirectories(directory)
        val probe = Files.createTempFile(directory, ".write", ".probe")
        Files.deleteIfExists(probe)
        directory.toAbsolutePath().normalize()
    }.getOrNull()

    private fun findAppRoot(): Path? {
        // jpackage 的启动器把自己的路径放在这个属性里
        System.getProperty("jpackage.app-path")?.let { launcher ->
            Path.of(launcher).toAbsolutePath().parent?.let { return it }
        }
        // 资源目录是 <程序目录>/app/resources
        System.getProperty("compose.application.resources.dir")?.let { resources ->
            Path.of(resources).toAbsolutePath().parent?.parent?.let { return it }
        }
        return null
    }
}
