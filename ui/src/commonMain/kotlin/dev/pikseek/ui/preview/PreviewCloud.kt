package dev.pikseek.ui.preview

import dev.piko.shared.log.PikoLog
import dev.piko.ui.PikoServices
import dev.pikseek.platform.currentTimeMillis
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.RatingBook
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

    /** [gcid] 的进度条分段，没有或读不懂为 null。 */
    suspend fun loadMarks(gcid: String): MediaMarks?

    /** 存进度条分段，换掉同一个视频原有的。 */
    suspend fun saveMarks(marks: MediaMarks)

    /** 网盘上的「讨厌」名单，没有为空名单；[fresh] 为 true 时先重列，拿别的设备刚传上去的。读不出来时抛出。 */
    suspend fun loadRatings(fresh: Boolean = false): RatingBook = RatingBook()

    /** 存「讨厌」名单，原位换掉网盘上原有的那一份。 */
    suspend fun saveRatings(book: RatingBook) {}
}

/**
 * 网盘上的预览缓存：根目录下的 [FOLDER_NAME] 文件夹，每个视频一个包，名字见 [PreviewPackName]；
 * 进度条分段（[MediaMarks]）也放在这里，每个视频一个小文件；「讨厌」名单（[RatingBook]）全账号一个文件。
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

    // 分段文件：gcid → 文件 ID
    private var marksFiles: Map<String, String> = emptyMap()

    // 「讨厌」名单的文件 ID，没有为 null
    private var ratingsFile: String? = null

    private val drive get() = services.driveRepository

    /** 重列网盘上的预览缓存文件夹。[force] 为 false 时列过不久就不再列。失败时保留原来的结果。 */
    override suspend fun refresh(force: Boolean) {
        lock.withLock {
            checkAccount()
            if (!force && listedAt != 0L && currentTimeMillis() - listedAt < STALE_MS) return
            try {
                val folder = findFolder() ?: run {
                    _packs.value = emptyMap()
                    marksFiles = emptyMap()
                    ratingsFile = null
                    listedAt = currentTimeMillis()
                    return
                }
                val listing = drive.listAllFiles(folder).getOrThrow()
                val found = HashMap<String, CloudPack>()
                val marks = HashMap<String, String>()
                var ratings: String? = null
                for (file in listing) {
                    // 上传到一半被打断的空壳读不出内容，不算
                    if (file.isFolder || file.phase != TaskPhase.COMPLETE) continue
                    MediaMarks.gcidOf(file.name)?.let { marks[it] = file.id }
                    // 正名的优先：改回原名之前那一刻列到的可能是带「(1)」的新文件与旧文件并存
                    if (RatingBook.isFileName(file.name) && (ratings == null || file.name == RatingBook.FILE_NAME)) ratings = file.id
                    val name = PreviewPackName.parse(file.name) ?: continue
                    val pack = CloudPack(file.id, name)
                    found[name.gcid] = better(found[name.gcid], pack)
                }
                _packs.value = found
                marksFiles = marks
                ratingsFile = ratings
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
        // 同一个视频的旧包：别处（另一台设备）同时传上去、这边还没列到的也算
        val fileId = replaceFile(folder, name.fileName, bytes, known = _packs.value[name.gcid]?.fileId) { file ->
            file.uppercase().startsWith(name.gcid) && file.endsWith(PreviewPackName.EXTENSION)
        }
        val pack = CloudPack(fileId, name)
        _packs.value = _packs.value + (name.gcid to pack)
        pack
    }

    override suspend fun loadMarks(gcid: String): MediaMarks? {
        if (gcid.isBlank()) return null
        refresh()
        val fileId = lock.withLock { marksFiles[gcid.uppercase()] } ?: return null
        return MediaMarks.decode(drive.readBytes(fileId).getOrThrow())
    }

    /** 传上去，成功后删掉同一个视频原有的分段文件。先传后删：传失败时原来的还在。 */
    override suspend fun saveMarks(marks: MediaMarks) = lock.withLock {
        checkAccount()
        val folder = findFolder() ?: drive.folderNamed("", FOLDER_NAME).getOrThrow().also { folderId = it }
        val gcid = marks.gcid.uppercase()
        val fileId = replaceFile(folder, marks.fileName, marks.encode(), known = marksFiles[gcid]) { file ->
            file.uppercase().startsWith(gcid) && file.endsWith(MediaMarks.EXTENSION)
        }
        marksFiles = marksFiles + (gcid to fileId)
    }

    override suspend fun loadRatings(fresh: Boolean): RatingBook {
        refresh(force = fresh)
        val fileId = lock.withLock { ratingsFile } ?: return RatingBook()
        // 读不懂（比如更新的版本写的）时当作空名单会在下次存的时候冲掉别人的，宁可这次不存
        return RatingBook.decode(drive.readBytes(fileId).getOrThrow()) ?: error("讨厌名单读不懂")
    }

    /** 传上去，成功后删掉原来的那一份。先传后删：传失败时原来的还在。 */
    override suspend fun saveRatings(book: RatingBook) = lock.withLock {
        checkAccount()
        val folder = findFolder() ?: drive.folderNamed("", FOLDER_NAME).getOrThrow().also { folderId = it }
        ratingsFile = replaceFile(folder, RatingBook.FILE_NAME, book.encode(), known = ratingsFile, isSame = RatingBook::isFileName)
    }

    /**
     * 传一个文件换掉旧的：先传，成功后彻底删掉 [isSame] 认出的旧文件（不进回收站：旧的只是缓存，进回收站只会把它塞满）。
     * 网盘上传同名文件不覆盖，而是给新的另起一个带序号的名字：旧的删掉之后把新的改回 [name]，不然下次列表认不出它。
     * [known] 是记着的旧文件，列不出文件夹时删它。调用方持锁。
     */
    private suspend fun replaceFile(folder: String, name: String, bytes: ByteArray, known: String?, isSame: (String) -> Boolean): String {
        val fileId = drive.uploadBytes(folder, name, bytes).getOrThrow()
        val listing = runCatching { drive.listAllFiles(folder).getOrThrow() }.getOrNull()
        val stale = listing?.filter { it.id != fileId && isSame(it.name) }?.map { it.id }
            ?: listOfNotNull(known?.takeIf { it != fileId })
        if (stale.isNotEmpty()) {
            drive.deletePermanently(stale).onFailure { PikoLog.w(TAG, "删旧文件失败：${it::class.simpleName}") }
        }
        val renamed = listing?.firstOrNull { it.id == fileId }?.let { it.name != name }
            ?: (listing?.any { it.id != fileId && it.name == name } ?: false)
        if (renamed) drive.rename(fileId, name).onFailure { PikoLog.w(TAG, "改回原名失败：${it::class.simpleName}") }
        return fileId
    }

    /** 换了账号：清掉上一个账号的一切。调用方持锁。 */
    private fun checkAccount() {
        val current = services.clientManager.currentClient.value?.account
        if (current == account) return
        account = current
        folderId = null
        listedAt = 0L
        _packs.value = emptyMap()
        marksFiles = emptyMap()
        ratingsFile = null
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
