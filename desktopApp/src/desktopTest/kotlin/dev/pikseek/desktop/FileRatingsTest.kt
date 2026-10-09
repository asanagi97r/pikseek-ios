package dev.pikseek.desktop

import dev.piko.shared.state.FileRating
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.RatingBook
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewPackStore
import dev.pikseek.ui.rating.FileRatings
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 收藏与讨厌：单击、双击、取消，星标与讨厌名单怎么改，几台设备的名单怎么合并。网盘与星标接口都是假的。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileRatingsTest {
    private val gcidA = "A".repeat(40)
    private val gcidB = "B".repeat(40)
    private val video = video("v1", gcidA)
    private val other = video("v2", gcidB)

    @Test
    fun `click likes, click again clears, double click dislikes`() = runTest {
        val (ratings, store, stars) = setUp()
        ratings.click(video)
        assertEquals(FileRating.LIKED, ratings.ratingOf(video), "单击当场就是收藏，不等接口")
        settle()
        assertEquals(listOf("v1" to true), stars)

        ratings.click(video)
        assertEquals(FileRating.NONE, ratings.ratingOf(video))
        ratings.doubleClick(video)
        assertEquals(FileRating.DISLIKED, ratings.ratingOf(video))
        settle()
        assertEquals(listOf("v1" to true, "v1" to false), stars, "讨厌之前已取消收藏，不再多发一次")
        assertTrue(store.saved.last().isDisliked(gcidA), "讨厌名单传上了网盘")

        // 讨厌的再单击是取消；双击收藏了的是讨厌（顺手取消星标）
        ratings.click(video)
        assertEquals(FileRating.NONE, ratings.ratingOf(video))
        ratings.click(video)
        ratings.doubleClick(video)
        assertEquals(FileRating.DISLIKED, ratings.ratingOf(video))
        settle()
        assertFalse(ratings.isStarred(video))
    }

    @Test
    fun `quick clicks are saved together`() = runTest {
        val (ratings, store, _) = setUp()
        ratings.doubleClick(video)
        ratings.doubleClick(other)
        settle()
        assertEquals(1, store.saved.size, "攒一会儿一起传")
        assertTrue(store.saved.single().isDisliked(gcidA) && store.saved.single().isDisliked(gcidB))
    }

    @Test
    fun `saving merges with what another device stored meanwhile`() = runTest {
        val (ratings, store, _) = setUp()
        settle()
        // 另一台设备在这之后讨厌了 B
        store.remote = RatingBook().with(gcidB, disliked = true, atMs = 5)
        ratings.doubleClick(video)
        settle()
        val saved = store.saved.last()
        assertTrue(saved.isDisliked(gcidA))
        assertTrue(saved.isDisliked(gcidB), "另一台的不能被冲掉")
        assertEquals(FileRating.DISLIKED, ratings.ratingOf(other), "传完以网盘上合并后的为准")
    }

    @Test
    fun `the listing wins once it reflects a change`() = runTest {
        val (ratings, _, _) = setUp()
        ratings.click(video)
        settle()
        // 列表重列之后带上了星标：照列表
        val relisted = video.copy(tags = listOf(FileTag("t", "STAR", 0)))
        assertEquals(FileRating.LIKED, ratings.ratingOf(relisted))
        ratings.settle(listOf(relisted))
        // 之后在别处（右键菜单、网页版）取消了星标，列表又变回没星标：照列表，不再用本机记着的
        assertEquals(FileRating.NONE, ratings.ratingOf(video))
    }

    @Test
    fun `failed star call is rolled back`() = runTest {
        val (ratings, _, _) = setUp(starFails = true)
        ratings.click(video)
        assertEquals(FileRating.LIKED, ratings.ratingOf(video))
        settle()
        assertEquals(FileRating.NONE, ratings.ratingOf(video), "接口失败，退回原样")
    }

    @Test
    fun `folders can be liked but not disliked`() = runTest {
        val (ratings, _, stars) = setUp()
        val folder = FileStat(kind = FileKind.FOLDER, id = "f", name = "合集")
        assertFalse(ratings.canDislike(folder))
        ratings.doubleClick(folder)
        assertEquals(FileRating.LIKED, ratings.ratingOf(folder), "文件夹双击当单击")
        settle()
        assertEquals(listOf("f" to true), stars)
    }

    /** 让后台的活都跑完：advanceUntilIdle 不等 backgroundScope 里的，只好把虚拟时间往前拨过攒着传的那一小会儿。 */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun TestScope.setUp(starFails: Boolean = false): Triple<FileRatings, MemoryRatings, MutableList<Pair<String, Boolean>>> {
        val store = MemoryRatings()
        val stars = ArrayList<Pair<String, Boolean>>()
        var clock = 0L
        val ratings = FileRatings(
            scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job) + CoroutineExceptionHandler { _, e -> e.printStackTrace() }),
            store = store,
            setStarred = { ids, starred ->
                if (starFails) Result.failure(IllegalStateException("网络断了")) else Result.success(Unit).also { ids.forEach { stars += it to starred } }
            },
            accounts = MutableStateFlow("me"),
            now = { ++clock + testScheduler.currentTime },
        )
        // 先让它读完账号与网盘上的名单，再开始点
        settle()
        return Triple(ratings, store, stars)
    }

    private fun video(id: String, gcid: String) = FileStat(kind = "drive#file", id = id, name = "$id.mp4", size = "1000000", hash = gcid)

    private class MemoryRatings : PreviewPackStore {
        var remote = RatingBook()
        val saved = ArrayList<RatingBook>()

        override suspend fun loadRatings(fresh: Boolean): RatingBook = remote

        override suspend fun saveRatings(book: RatingBook) {
            remote = book
            saved += book
        }

        override val packs: StateFlow<Map<String, CloudPack>> = MutableStateFlow(emptyMap())

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun lookup(gcid: String): CloudPack? = null

        override suspend fun download(pack: CloudPack): ByteArray = error("不用")

        override suspend fun upload(name: PreviewPackName, bytes: ByteArray): CloudPack = error("不用")

        override suspend fun loadMarks(gcid: String): MediaMarks? = null

        override suspend fun saveMarks(marks: MediaMarks) = Unit
    }
}
