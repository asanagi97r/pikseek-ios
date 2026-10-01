package dev.pikseek.ios

import dev.piko.data.auth.KeyValueSettings
import dev.piko.shared.download.PikoDownloadStorage
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSUserDefaults

/** Piko 的偏好在 iOS 上的落盘处：程序自己的 NSUserDefaults，键加前缀。里面没有机密。 */
internal class IosKeyValueSettings(private val prefix: String = "piko.") : KeyValueSettings {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String, default: String): String = defaults.stringForKey(prefix + key) ?: default

    override fun set(key: String, value: String) {
        defaults.setObject(value, forKey = prefix + key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(prefix + key)
    }

    override fun keysWithPrefix(prefix: String): List<String> {
        val full = this.prefix + prefix
        return defaults.dictionaryRepresentation().keys
            .mapNotNull { (it as? String)?.takeIf { key -> key.startsWith(full) }?.removePrefix(this.prefix) }
            .sorted()
    }
}

/** 文件是否存在、多大。读不到一律当没有。 */
internal object IosFiles {
    fun exists(path: String): Boolean = SystemFileSystem.metadataOrNull(Path(path)) != null

    fun length(path: String): Long = SystemFileSystem.metadataOrNull(Path(path))?.takeIf { it.isRegularFile }?.size ?: 0L

    fun writeText(path: String, text: String) {
        Path(path).parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(text.encodeToByteArray()) }
    }
}

/**
 * 下载落在程序的 Documents/Downloads 里：在「文件」App 的「我的 iPhone → PikSeek」下看得到，也能从那里拷走。
 * iOS 上程序只能往自己的沙盒里写，所以位置是固定的，不给选。
 */
internal class IosDownloadStorage(private val root: String) : PikoDownloadStorage {
    override fun pathFor(fileName: String): String = "$root/$fileName"

    override suspend fun downloadTarget(fileName: String): String {
        val target = Path(pathFor(fileName))
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        return target.toString()
    }

    // 直接写在最终位置上，没有暂存这一步
    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath

    override suspend fun existingLength(fileName: String): Long = IosFiles.length(pathFor(fileName))

    override suspend fun exists(fileName: String): Boolean = IosFiles.exists(pathFor(fileName))

    override suspend fun delete(path: String): Boolean = runCatching {
        SystemFileSystem.delete(Path(path), mustExist = false)
        true
    }.getOrDefault(false)

    override suspend fun locate(fileName: String): String? = pathFor(fileName).takeIf(IosFiles::exists)

    override suspend fun pruneEmptyFolders(folder: String) {
        runCatching { prune(Path(pathFor(folder))) }
    }

    /** 删掉空的子文件夹；自己也空了就一起删。返回这个文件夹是不是删掉了。 */
    private fun prune(directory: Path): Boolean {
        if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) return false
        var empty = true
        for (child in SystemFileSystem.list(directory)) {
            val removed = SystemFileSystem.metadataOrNull(child)?.isDirectory == true && prune(child)
            if (!removed) empty = false
        }
        if (empty) SystemFileSystem.delete(directory, mustExist = false)
        return empty
    }
}

/**
 * 待上传的本机文件。uri 是文件选择器交回来的路径：选中的文件已由 Swift 一侧拷进程序的临时目录
 * （选择器给的原始位置只在那一刻有权读），所以这里读的是自己沙盒里的普通文件，用完删掉。
 */
internal class IosUploadSources : PikoUploadSources {
    override fun describe(uri: String): UploadSourceInfo? {
        val path = Path(uri)
        val metadata = SystemFileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile } ?: return null
        // 拷进来的副本不会再变，修改时刻给个定值即可：续传时比对的是「和任务里记的一样不一样」
        return UploadSourceInfo(path.name, metadata.size, 0L)
    }

    override fun readAt(uri: String, offset: Long, length: Int): ByteArray =
        SystemFileSystem.source(Path(uri)).buffered().use { source ->
            source.skip(offset)
            source.readByteArray(length)
        }

    override fun open(uri: String, offset: Long): RawSource {
        val source = SystemFileSystem.source(Path(uri)).buffered()
        if (offset > 0) source.skip(offset)
        return source
    }

    // 文件选择器只交回文件，不展开文件夹
    override fun listFolder(uri: String): UploadFolder? = null

    override fun release(uri: String) {
        runCatching { SystemFileSystem.delete(Path(uri), mustExist = false) }
    }
}
