package dev.pikseek.ui.preview

import dev.piko.shared.log.PikoLog
import dev.piko.ui.PikoServices
import dev.pikseek.platform.currentTimeMillis
import dev.pikseek.thumbnail.PreviewPackName
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 网盘上的一个预览包。 */
class CloudPack(val fileId: String, val name: PreviewPackName)

/** 预览包存在哪里。眼下只有 [PreviewCloud]（网盘）；测试里换成内存里的。 */
interface PreviewPackStore {
    /** 已有的预览包，gcid → 包。 */
    val packs: StateFlow<Map<String, CloudPack>>

    suspend fun refresh(force: Boolean = false)

    suspend fun lookup(gcid: String): CloudPack?

    suspend fun download(pack: CloudPack): ByteArray

    /** 传一个包上去，换掉同一个视频原有的。 */
    suspend fun upload(name: PreviewPackName, bytes: ByteArray): CloudPack
}

/**
 * 网盘上的预览缓存：根目录下的 [FOLDER_NAME] 文件夹，每个视频一个包，名字见 [PreviewPackName]。
 *
 * 列一次这个文件夹就知道全部视频的预览做到了哪，结果记在 [packs] 里（gcid → 包），文件列表上的角标照它画。
 * 列表过了 [STALE_MS] 才重列；自己传上去、删掉的当场改进去，不等重列。换了账号整个清掉重来。
 *
 * 存取都经上游现成的小文件读写（归档清单、设置同步也是这么存的）：整个包在内存里，一个视频几百 KB。
 */
class PreviewCloud(private val services: PikoServices) : PreviewPackStore {
    private val lock = Mutex()
    private var account: String? = null
    private var folderId: String? = null
    private var listedAt = 0L

    private val _packs = MutableStateFlow<Map<String, CloudPack>>(emptyMap())
    override val packs: StateFlow<Map<String, CloudPack>> = _packs.asStateFlow()

    private val drive get() = services.driveRepository

    /** 重列网盘上的预览缓存文件夹。[force] 为 false 时列过不久就不再列。失败时保留原来的结果。 */
    override suspend fun refresh(force: Boolean) {
        lock.withLock {
            checkAccount()
            if (!force && listedAt != 0L && currentTimeMillis() - listedAt < STALE_MS) return
            try {
                val folder = findFolder() ?: run {
                    _packs.value = emptyMap()
                    listedAt = currentTimeMillis()
                    return
                }
                val listing = drive.listAllFiles(folder).getOrThrow()
                val found = HashMap<String, CloudPack>()
                for (file in listing) {
                    // 上传到一半被打断的空壳读不出内容，不算
                    if (file.isFolder || file.phase != TaskPhase.COMPLETE) continue
                    val name = PreviewPackName.parse(file.name) ?: continue
                    val pack = CloudPack(file.id, name)
                    found[name.gcid] = better(found[name.gcid], pack)
                }
                _packs.value = found
                listedAt = currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "列网盘上的预览缓存失败：${e::class.simpleName}")
            }
        }
    }

    /** [gcid] 的预览包，没有为 null。列表旧了先重列。 */
    override suspend fun lookup(gcid: String): CloudPack? {
        if (gcid.isBlank()) return null
        refresh()
        return _packs.value[gcid.uppercase()]
    }

    override suspend fun download(pack: CloudPack): ByteArray = drive.readBytes(pack.fileId).getOrThrow()

    /**
     * 传一个包上去，成功后删掉同一个视频原有的包（原位覆盖）。先传后删：传失败时原来的还在。
     */
    override suspend fun upload(name: PreviewPackName, bytes: ByteArray): CloudPack = lock.withLock {
        checkAccount()
        val folder = findFolder() ?: drive.folderNamed("", FOLDER_NAME).getOrThrow().also { folderId = it }
        val fileId = drive.uploadBytes(folder, name.fileName, bytes).getOrThrow()
        val pack = CloudPack(fileId, name)
        // 同一个视频的旧包：记在表里的那个，以及别处（另一台设备）同时传上去、这边还没列到的
        val stale = runCatching { drive.listAllFiles(folder).getOrThrow() }.getOrDefault(emptyList())
            .filter { it.id != fileId && PreviewPackName.parse(it.name)?.gcid == name.gcid }
            .map { it.id }
            .ifEmpty { listOfNotNull(_packs.value[name.gcid]?.fileId?.takeIf { it != fileId }) }
        if (stale.isNotEmpty()) {
            // 彻底删除而不是移入回收站：旧包只是缓存，进回收站只会把回收站塞满
            drive.deletePermanently(stale).onFailure { PikoLog.w(TAG, "删旧的预览包失败：${it::class.simpleName}") }
        }
        _packs.value = _packs.value + (name.gcid to pack)
        pack
    }

    /** 换了账号：清掉上一个账号的一切。调用方持锁。 */
    private fun checkAccount() {
        val current = services.clientManager.currentClient.value?.account
        if (current == account) return
        account = current
        folderId = null
        listedAt = 0L
        _packs.value = emptyMap()
    }

    /** 根目录下的预览缓存文件夹，没有为 null（不新建：只读的时候不该往网盘里添东西）。调用方持锁。 */
    private suspend fun findFolder(): String? {
        folderId?.let { return it }
        val root = drive.listAllFiles("").getOrThrow()
        return root.firstOrNull { it.isFolder && it.name == FOLDER_NAME && !it.trashed }?.id?.also { folderId = it }
    }

    private fun better(a: CloudPack?, b: CloudPack): CloudPack = when {
        a == null -> b
        b.name.done > a.name.done -> b
        else -> a
    }

    companion object {
        /** 网盘根目录下放预览包的文件夹。点开头：多数文件管理器把它当系统文件夹，排在最前，一看就知道不是自己的东西。 */
        const val FOLDER_NAME = ".PikSeek-PreviewCache"

        private const val STALE_MS = 5 * 60_000L
        private const val TAG = "PreviewCloud"
    }
}
