package dev.pikseek.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

/**
 * PikSeek 在 iOS 上写文件的去处，都在程序自己的沙盒里。
 *
 * - 要留着的（设置、Piko 的偏好、日志）：Application Support/PikSeek
 * - 能重建的（预览缓存、图片缓存、片段缓存）：Caches/PikSeek，空间紧张时系统可以清掉
 * - 临时文件：系统给这个程序的 tmp
 *
 * 登录会话不在文件里，在钥匙串。
 */
object IosPaths {
    const val APP_NAME = "PikSeek"

    val dataRoot: String by lazy { directory(NSApplicationSupportDirectory) }
    val cacheRoot: String by lazy { directory(NSCachesDirectory) }

    val logs: String get() = dir(dataRoot, "logs")
    val cache: String get() = dir(cacheRoot, "cache")
    val thumbnails: String get() = dir(cacheRoot, "thumbnails")
    val temp: String get() = dir(NSTemporaryDirectory().trimEnd('/'), APP_NAME)

    fun dir(parent: String, name: String): String = "$parent/$name".also { SystemFileSystem.createDirectories(Path(it)) }

    /** 清掉上次运行留下的临时文件。删不掉的跳过。 */
    fun cleanTempDirectory() {
        runCatching { deleteRecursively(Path(temp), keepRoot = true) }
    }

    private fun deleteRecursively(path: Path, keepRoot: Boolean) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach { deleteRecursively(it, keepRoot = false) }
        }
        if (!keepRoot) SystemFileSystem.delete(path, mustExist = false)
    }

    private fun directory(kind: ULong): String {
        val base = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String
            ?: error("找不到程序的数据目录")
        return "$base/$APP_NAME".also { SystemFileSystem.createDirectories(Path(it)) }
    }
}

/** iOS 上的设置：程序自己的偏好（NSUserDefaults），键加前缀。 */
class UserDefaultsSettingsStore(private val prefix: String = "pikseek.") : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun load(): Map<String, String> {
        val result = HashMap<String, String>()
        defaults.dictionaryRepresentation().forEach { (key, value) ->
            val name = key as? String ?: return@forEach
            if (name.startsWith(prefix) && value is String) result[name.removePrefix(prefix)] = value
        }
        return result
    }

    override fun save(values: Map<String, String>) {
        values.forEach { (key, value) -> defaults.setObject(value, forKey = prefix + key) }
    }
}
