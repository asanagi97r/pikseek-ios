package dev.pikseek.desktop

import dev.pikseek.thumbnail.AudioPrint
import dev.pikseek.thumbnail.EpisodeMatcher
import dev.pikseek.thumbnail.MediaMarks
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // 一部剧的三集，每集 24 分钟；还有一个 2 小时的（不是剧集，不认片头片尾）
        "series" to listOf(
            episode("e3", "第03话.mp4", "3".repeat(40)),
            episode("e1", "第01话.mp4", "1".repeat(40)),
            episode("e2", "第02话.mp4", "2".repeat(40)),
            FileStat(kind = "drive#file", id = "movie", name = "剧场版.mp4", parentId = "series", size = "1000000", hash = "9".repeat(40), params = mapOf("duration" to "7200")),
        ),
        "hls" to listOf(
            FileStat(kind = "drive#file", id = "m3u8-ok", name = "ok.m3u8", mimeType = "video/mpegurl", size = "1000", hash = "D".repeat(40)),
            FileStat(kind = "drive#file", id = "m3u8-none", name = "none.m3u8", mimeType = "video/mpegurl", size = "1000", hash = "F".repeat(40)),
        ),
    )

    private val jobs = PreviewCacheJobs(
        scope = scope,
        cloud = store,
        cache = cache,
        engine = ThumbnailEngine(cache),
        listFolder = { folders.getValue(it) },
        openSources = { _, durationMs -> listOf(FakeSource(durationMs)) },
        // 列表里没时长的：一个查得到（文件详情里有），一个查不到
        probeDurationMs = { id -> if (id == "m3u8-ok") 600_000L else 0L },
        episodeAudio = { id, durationMs -> audioRequests += id; episodeSound(id, durationMs) },
    )

    private val audioRequests = ConcurrentHashMap.newKeySet<String>()
    private val rate = AudioPrint.SAMPLE_RATE

    /** 片头曲在第 n 集的开场戏之后：第 1 集 20 秒、第 2 集 40 秒、第 3 集 60 秒，长 80 秒；片尾曲在结尾那段的第 30 秒起。 */
    private fun introStartSeconds(id: String): Int = 20 * (id.removePrefix("e").toIntOrNull() ?: 1)

    private fun episodeSound(id: String, durationMs: Long): EpisodeMatcher.Episode {
        val n = id.removePrefix("e").toIntOrNull() ?: 0
        val cold = introStartSeconds(id)
        val head = join(noise(cold, 10 + n), song(80, 7), noise(300 - cold - 80, 20 + n))
        val tail = join(noise(30, 30 + n), song(70, 8), noise(240 - 30 - 70, 40 + n))
        return EpisodeMatcher.Episode(id, AudioPrint.of(head, 0), AudioPrint.of(tail, durationMs - 240_000))
    }

    private fun song(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        val out = ShortArray(seconds * rate)
        var chord = DoubleArray(3)
        var texture = 0.0
        var last = 0.0
        for (i in out.indices) {
            if (i % (rate / 4) == 0) chord = DoubleArray(3) { 200.0 + random.nextDouble() * 2300 }
            if (i % (rate / 8) == 0) texture = 500 + random.nextDouble() * 2500
            last = 0.5 * last + 0.5 * (random.nextDouble() - 0.5) * 2 * texture
            out[i] = (chord.sumOf { sin(2 * PI * it * i / rate) } * 3000 + last).toInt().coerceIn(-32000, 32000).toShort()
        }
        return out
    }

    private fun noise(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        var level = 0.0
        var last = 0.0
        return ShortArray(seconds * rate) { i ->
            if (i % (rate / 5) == 0) level = random.nextDouble() * 6000
            last = 0.7 * last + 0.3 * (random.nextDouble() - 0.5) * 2 * level
            last.toInt().toShort()
        }
    }

    private fun join(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

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
    fun missingListedDurationIsLookedUpAndUnfindableOnesSayWhy(): Unit = runBlocking {
        val state = run(PreviewCacheRequest(PreviewCacheTarget.Folder("hls", "hls"), PreviewDensity.Medium, includeSubfolders = false, overwrite = false))
        assertEquals(1, state.made, "列表里没时长、详情里查得到的照做")
        assertEquals(setOf("D".repeat(40)), store.packs.value.keys)
        assertEquals(mapOf("拿不到时长" to 1), state.failures)
        assertTrue(state.summary!!.contains("拿不到时长 1"), state.summary)
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

    @Test
    fun scenesAreMarkedOnceAndHandEditedOnesAreLeftAlone(): Unit = runBlocking {
        val request = PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.Medium, includeSubfolders = true, overwrite = false, scenes = true)
        val state = run(request)
        assertEquals(2, state.marked)
        val marks = assertNotNull(store.marks[gcidA])
        assertTrue(marks.scenesDone)
        assertEquals(gcidA, marks.gcid)
        assertTrue(marks.scenes.size <= 10 && marks.scenes.all { it in 1 until 600_000 }, "${marks.scenes}")
        assertTrue(state.summary!!.contains("场景分点 2 个"), state.summary)

        // 再跑一次：做过的不再做
        assertEquals(0, run(request).marked)
        // 手改过的：连「已有的也重做」也不动
        val edited = marks.copy(scenes = emptyList()).withScene(300_000)
        store.marks[gcidA] = edited
        run(PreviewCacheRequest(PreviewCacheTarget.Folder("root", "根"), PreviewDensity.Medium, includeSubfolders = true, overwrite = true, scenes = true))
        assertEquals(listOf(300_000L), store.marks.getValue(gcidA).scenes)
    }

    @Test
    fun aFailedRedoStillMarksScenesFromThePackAlreadyOnTheDrive(): Unit = runBlocking {
        val file = folders.getValue("root").first()
        run(PreviewCacheRequest(PreviewCacheTarget.Files(listOf(file)), PreviewDensity.Medium, false, true))
        // 另一台设备：本机没有缓存，原画一帧都读不下来
        val otherCache = ThumbnailCache(Files.createTempDirectory("preview-broken").toString(), WebpSpriteCodec)
        val broken = PreviewCacheJobs(
            scope = scope,
            cloud = store,
            cache = otherCache,
            engine = ThumbnailEngine(otherCache),
            listFolder = { folders.getValue(it) },
            openSources = { _, _ -> listOf(DeadSource()) },
        )
        broken.enqueue(PreviewCacheRequest(PreviewCacheTarget.Files(listOf(file)), PreviewDensity.Medium, false, true, scenes = true))
        val state = withTimeout(60_000) {
            while (!broken.state.value.running && broken.state.value.summary == null) delay(20)
            while (broken.state.value.running || broken.state.value.summary == null) delay(20)
            broken.state.value
        }
        assertEquals(mapOf("取不出画面（没有转码流，原画读不下来）" to 1), state.failures)
        assertEquals(1, state.marked, "预览没重做成，分点照样用网盘上的包做")
        assertTrue(store.marks.getValue(gcidA).scenesDone)
        assertTrue(store.packs.value.getValue(gcidA).name.isComplete, "网盘上原来的包还在")
    }

    private class DeadSource : ThumbnailSource {
        override val description = "原画"
        override val maxParallel = 1
        override val networkBytes = 0L

        override suspend fun frameNear(timeMs: Long): ThumbnailFrame? = null

        override fun close() = Unit
    }

    @Test
    fun episodesInAFolderGetTheirIntroAndOutro(): Unit = runBlocking {
        val state = run(PreviewCacheRequest(PreviewCacheTarget.Folder("series", "剧"), PreviewDensity.Low, includeSubfolders = false, overwrite = false, episodes = true))
        assertEquals(3, state.episodesChecked)
        assertEquals(3, state.episodesFound)
        assertTrue("movie" !in audioRequests, "两小时的不是剧集，不去读它的声音")
        for (n in 1..3) {
            val marks = assertNotNull(store.marks[n.toString().repeat(40)], "第 $n 集")
            val intro = assertNotNull(marks.intro, "第 $n 集的片头")
            assertTrue(abs(intro.startMs - introStartSeconds("e$n") * 1000L) <= 1_500, "第 $n 集片头开头 ${intro.startMs}")
            assertTrue(abs(intro.lengthMs - 80_000) <= 3_000, "第 $n 集片头长 ${intro.lengthMs}")
            val outro = assertNotNull(marks.outro, "第 $n 集的片尾")
            assertTrue(abs(outro.startMs - (1_440_000 - 240_000 + 30_000)) <= 1_500, "第 $n 集片尾开头 ${outro.startMs}")
            assertTrue(marks.episodeDone)
        }
        assertNull(store.marks["9".repeat(40)]?.intro)

        // 认过的不再认：一次都不再读声音
        audioRequests.clear()
        run(PreviewCacheRequest(PreviewCacheTarget.Folder("series", "剧"), PreviewDensity.Low, includeSubfolders = false, overwrite = false, episodes = true))
        assertTrue(audioRequests.isEmpty(), "又读了：$audioRequests")
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

    private fun episode(id: String, name: String, gcid: String) =
        FileStat(kind = "drive#file", id = id, name = name, parentId = "series", size = "1000000", hash = gcid, params = mapOf("duration" to "1440"))

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

        val marks = ConcurrentHashMap<String, MediaMarks>()

        override suspend fun loadMarks(gcid: String): MediaMarks? = marks[gcid.uppercase()]

        override suspend fun saveMarks(marks: MediaMarks) {
            this.marks[marks.gcid.uppercase()] = marks
        }
    }
}
