package dev.pikseek.ui.rating

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.shared.log.PikoLog
import dev.piko.shared.state.FileRating
import dev.pikseek.platform.currentTimeMillis
import dev.pikseek.thumbnail.RatingBook
import dev.pikseek.ui.preview.PreviewPackStore
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 收藏与讨厌。收藏就是网盘的星标，网页版与别的客户端都看得到；讨厌记在 [RatingBook] 里，
 * 存到网盘上预览缓存的文件夹，全账号一个文件，电脑与 iPad 共用。
 *
 * 点了当场就变（星标先记在 [stars] 里，列表重列之后以网盘为准；讨厌先改 [book]），再在后台传：
 * 讨厌名单攒一小会儿一起传，连点十几个只传一两次。传之前先读网盘上的那份合并，另一台设备刚改的不会被冲掉。
 *
 * 状态都是快照状态，在主线程上改：列表的筛选（DriveScreenState.ratingOf）读它们，跟着变。
 *
 * @param scope 跑在主线程上的作用域
 * @param accounts 当前账号，换了账号名单清空重读
 */
class FileRatings(
    private val scope: CoroutineScope,
    private val store: PreviewPackStore,
    private val setStarred: suspend (ids: List<String>, starred: Boolean) -> Result<Unit>,
    accounts: Flow<String?>,
    private val now: () -> Long = ::currentTimeMillis,
) {
    /** 讨厌名单：网盘上的合并本机还没传上去的。 */
    var book by mutableStateOf(RatingBook())
        private set

    // 本机改过、还没传上去的
    private var pending = RatingBook()

    // 本机刚改的星标：文件 ID → 改之前、改之后。列表里的那一项还是改之前的样子时以「改之后」为准
    private class StarChange(val before: Boolean, val after: Boolean)

    private val stars = mutableStateMapOf<String, StarChange>()

    private val starLock = Mutex()
    private val syncLock = Mutex()
    private var syncedAt = 0L
    private var account: String? = null

    private val saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 失败时给用户的一句话。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        scope.launch {
            accounts.distinctUntilChanged().collect { current ->
                account = current
                book = RatingBook()
                pending = RatingBook()
                stars.clear()
                syncedAt = 0L
                if (current != null) sync()
            }
        }
        scope.launch {
            // 一个一个来：传的时候又点了的，等这一次传完再传一次
            saveRequests.collect {
                delay(SAVE_DELAY_MS)
                save()
            }
        }
    }

    fun ratingOf(file: FileStat): FileRating = when {
        isStarred(file) -> FileRating.LIKED
        canDislike(file) && book.isDisliked(file.hash) -> FileRating.DISLIKED
        else -> FileRating.NONE
    }

    fun isStarred(file: FileStat): Boolean {
        val change = stars[file.id] ?: return file.isStarred
        return if (file.isStarred == change.before) change.after else file.isStarred
    }

    /**
     * 新列出来的一批文件：已经带上本机改过的星标的，本机就不再记着，以后以列表为准。
     * 不忘掉的话，之后在别处（右键菜单、网页版）把星标改回去，列表回到「改之前」的样子，本机记着的又会冒出来。
     */
    fun settle(files: List<FileStat>) {
        if (stars.isEmpty()) return
        for (file in files) {
            val change = stars[file.id] ?: continue
            if (file.isStarred == change.after) stars.remove(file.id)
        }
    }

    /** 文件夹、没有 gcid 的（还在上传的）只能收藏，不能讨厌。 */
    fun canDislike(file: FileStat): Boolean = !file.isFolder && file.hash.isNotBlank()

    /** 单击：没标过的收藏，标过的（收藏或讨厌）取消。 */
    fun click(file: FileStat) = set(file, if (ratingOf(file) == FileRating.NONE) FileRating.LIKED else FileRating.NONE)

    /** 双击：讨厌。已经讨厌的再双击也是取消。 */
    fun doubleClick(file: FileStat) {
        if (!canDislike(file)) return click(file)
        set(file, if (ratingOf(file) == FileRating.DISLIKED) FileRating.NONE else FileRating.DISLIKED)
    }

    fun set(file: FileStat, rating: FileRating) {
        val wantStar = rating == FileRating.LIKED
        if (isStarred(file) != wantStar) changeStar(file, wantStar)
        if (canDislike(file)) {
            val wantDislike = rating == FileRating.DISLIKED
            if (book.isDisliked(file.hash) != wantDislike) changeDislike(file.hash, wantDislike)
        }
    }

    /** 把网盘上的名单读进来。读过不久（[SYNC_INTERVAL_MS]）就不再读，[force] 时照读。 */
    fun refresh(force: Boolean = false) {
        if (!force && syncedAt != 0L && now() - syncedAt < SYNC_INTERVAL_MS) return
        scope.launch { sync() }
    }

    private fun changeStar(file: FileStat, starred: Boolean) {
        val change = StarChange(before = file.isStarred, after = starred)
        stars[file.id] = change
        scope.launch {
            // 连点时按点的先后一个个发，后发的不会被先发的盖掉
            val result = starLock.withLock { setStarred(listOf(file.id), starred) }
            result.onFailure {
                PikoLog.w(TAG, "改星标失败：${it::class.simpleName}")
                if (stars[file.id] === change) stars.remove(file.id)
                _messages.tryEmit(if (starred) "收藏失败" else "取消收藏失败")
            }
        }
    }

    private fun changeDislike(gcid: String, disliked: Boolean) {
        val at = now()
        book = book.with(gcid, disliked, at)
        pending = pending.with(gcid, disliked, at)
        saveRequests.tryEmit(Unit)
    }

    private suspend fun sync() {
        val forAccount = account
        syncLock.withLock {
            try {
                val remote = store.loadRatings(fresh = syncedAt == 0L)
                if (account != forAccount) return
                book = remote.merge(pending)
                syncedAt = now()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "读讨厌名单失败：${e::class.simpleName}")
            }
        }
    }

    private suspend fun save() {
        val forAccount = account
        syncLock.withLock {
            val local = pending
            if (local.entries.isEmpty() || forAccount == null) return
            try {
                val merged = store.loadRatings(fresh = true).merge(local).pruned(now())
                store.saveRatings(merged)
                if (account != forAccount) return
                // 传的时候又点了的留着，下一次传
                pending = RatingBook(entries = pending.entries.filter { (gcid, entry) -> local.entries[gcid] != entry })
                book = merged.merge(pending)
                syncedAt = now()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "存讨厌名单失败：${e::class.simpleName}")
                _messages.tryEmit("讨厌名单没存上网盘，过一会儿再试")
                scope.launch {
                    delay(RETRY_DELAY_MS)
                    saveRequests.tryEmit(Unit)
                }
            }
        }
    }

    private companion object {
        const val TAG = "FileRatings"
        const val SAVE_DELAY_MS = 1_500L
        const val RETRY_DELAY_MS = 30_000L
        const val SYNC_INTERVAL_MS = 60_000L
    }
}

/** 没提供时为 null：别的窗口、测试里画文件卡片不带收藏按钮。 */
val LocalFileRatings = staticCompositionLocalOf<FileRatings?> { null }
