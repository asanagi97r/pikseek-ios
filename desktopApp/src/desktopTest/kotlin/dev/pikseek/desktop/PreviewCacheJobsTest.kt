package dev.pikseek.desktop

import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.ThumbnailSource
import dev.pikseek.ui.player.WebpSpriteCodec
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewCacheJobs
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewCacheTarget
import dev.pikseek.ui.preview.PreviewCloud
import dev.pikseek.ui.preview.PreviewJobsState
import dev.pikseek.ui.preview.PreviewPackStore
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 预览缓存的批量任务：找哪些视频、跳过哪些、按什么档次做、传上去叫什么。网盘换成内存里的，画面来源换成假的。
 * 不开窗口。
 */
class PreviewCacheJobsTest {
    private val directory = Files.createTempDirectory("preview-jobs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ThumbnailCache(directory.toString(), WebpSpriteCodec)
    private val store = MemoryStore()
    private val frameRequests = AtomicInteger()

    private val gcidA = "A".repeat(40)
    private val gcidC = "C".repeat(40)

    // 网盘：根目录有两个同内容的视频、一个文本、一个子文件夹（里面一个视频）、预览缓存自己的文件夹
    private val folders = mapOf(
        "root" to listOf(
            video("a", "a.mp4", gcidA),
            video("a-copy", "a 副本.mkv", gcidA),
            FileStat(kind = "drive#file", id = "txt", name = "notes.txt", size = "10"),
            folder("sub", "sub"),
            folder("cache", PreviewCloud.FOLDER_NAME),
        ),
        "sub" to listOf(video("c", "c.mp4", gcidC)),
        "cache" to listOf(video("never", "不该被扫到.mp4", "E".repeat(40))),
    )

    private val jobs = PreviewCacheJobs(
        scope = scope,
        cloud = store,
        cache = cache,
        engine = ThumbnailEngine(cache),
        listFolder = { folders.getValue(it) },
        openSources = { _, durationMs -> listOf(FakeSource(durationMs)) },
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun folderWithSubfoldersMakesOnePackPerContentAndSkipsTheCacheFolder(): Unit = runBlocking {
        val state = run(PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.Medium, includeSubfolders = true, overwrite = false))
        assertEquals(2, state.total, "同内容的两个视频只算一个，预览缓存文件夹不进去")
        assertEquals(2, state.made)
        assertEquals(setOf(gcidA, gcidC), store.packs.value.keys)
        // 10 分钟的片子中档 60 格，做满
        store.packs.value.values.forEach { assertEquals(PreviewPackName(it.name.gcid, PreviewDensity.Medium, 60, 60), it.name) }
        assertTrue(jobs.live.value.isEmpty(), "做完的视频从「正在做」里拿掉")
    }

    @Test
    fun withoutSubfoldersOnlyTheFolderItself(): Unit = runBlocking {
        val state = run(PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.Low, includeSubfolders = false, overwrite = false))
        assertEquals(1, state.made)
        assertEquals(setOf(gcidA), store.packs.value.keys)
        assertEquals(30, store.packs.value.getValue(gcidA).name.total)
    }

    @Test
    fun rerunSkipsFinishedOnesOfTheSameDensityAndRedoesOtherDensities(): Unit = runBlocking {
        val request = PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.Medium, includeSubfolders = true, overwrite = false)
        run(request)
        val uploadsBefore = store.uploads.get()
        val again = run(request)
        assertEquals(2, again.skipped)
        assertEquals(uploadsBefore, store.uploads.get(), "已有的不重传")

        val higher = run(PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.High, includeSubfolders = true, overwrite = false))
        assertEquals(2, higher.made)
        store.packs.value.values.forEach { assertEquals(PreviewDensity.High, it.name.density); assertEquals(120, it.name.total) }
        assertEquals(2, store.packs.value.size, "原位覆盖：一个视频只留一个包")
    }

    @Test
    fun namedVideoIsRedoneEvenWhenFinished(): Unit = runBlocking {
        val file = folders.getValue("root").first()
        run(PreviewCacheRequest(PreviewCacheTarget.Files(listOf(file)), PreviewDensity.Medium, includeSubfolders = false, overwrite = true))
        val requestsBefore = frameRequests.get()
        val again = run(PreviewCacheRequest(PreviewCacheTarget.Files(listOf(file)), PreviewDensity.Medium, includeSubfolders = false, overwrite = true))
        assertEquals(1, again.made)
        assertTrue(frameRequests.get() - requestsBefore >= 60, "重做是真的重新取帧，不是拿本机缓存交差")
    }

    @Test
    fun aPackMadeHereImportsIntoAnotherDeviceCache(): Unit = runBlocking {
        run(PreviewCacheRequest(PreviewCacheTarget.Files(listOf(folders.getValue("root").first())), PreviewDensity.Medium, false, true))
        val pack = store.packs.value.getValue(gcidA)
        val other = ThumbnailCache(Files.createTempDirectory("preview-other").toString(), WebpSpriteCodec)
        val imported = other.importPack(dev.pikseek.thumbnail.MediaFingerprint("another-id", gcidA, 1_000_000, 601_000), store.download(pack))
        assertEquals(60, imported?.frameCount)
    }

    private suspend fun run(request: PreviewCacheRequest): PreviewJobsState {
        jobs.enqueue(request)
        return withTimeout(120_000) {
            // 先等它开始，再等它做完
            while (!jobs.state.value.running && jobs.state.value.summary == null) delay(20)
            while (jobs.state.value.running || jobs.state.value.summary == null) delay(20)
            jobs.state.value
        }
    }

    private fun video(id: String, name: String, gcid: String) =
        FileStat(kind = "drive#file", id = id, name = name, size = "1000000", hash = gcid, params = mapOf("duration" to "600.4"))

    private fun folder(id: String, name: String) = FileStat(kind = FileKind.FOLDER, id = id, name = name)

    private inner class FakeSource(private val durationMs: Long) : ThumbnailSource {
        override val description = "假的"
        override val maxParallel = 2
        override val networkBytes = 0L

        override suspend fun frameNear(timeMs: Long): ThumbnailFrame {
            frameRequests.incrementAndGet()
            // 每秒一个关键帧；画面是灰的，不会被当成黑场重取
            val time = (timeMs / 1000 * 1000).coerceIn(0, durationMs - 1)
            return ThumbnailFrame(time, 32, 18, IntArray(32 * 18) { (0xFF808080).toInt() + (time / 1000).toInt() })
        }

        override fun close() = Unit
    }

    private class MemoryStore : PreviewPackStore {
        private val bytes = ConcurrentHashMap<String, ByteArray>()
        private val _packs = MutableStateFlow<Map<String, CloudPack>>(emptyMap())
        override val packs: StateFlow<Map<String, CloudPack>> = _packs
        val uploads = AtomicInteger()

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun lookup(gcid: String): CloudPack? = _packs.value[gcid.uppercase()]

        override suspend fun download(pack: CloudPack): ByteArray = bytes.getValue(pack.fileId)

        override suspend fun upload(name: PreviewPackName, bytes: ByteArray): CloudPack {
            val id = "pack-${uploads.incrementAndGet()}"
            this.bytes[id] = bytes
            return CloudPack(id, name).also { pack -> _packs.value = _packs.value + (name.gcid to pack) }
        }
    }
}
