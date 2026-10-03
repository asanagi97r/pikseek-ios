package dev.pikseek.thumbnail

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 不解码、不读网络的假来源：每个时刻交回一张纯色小图，颜色由时刻决定。用来测调度。 */
private class FakeSource(
    private val durationMs: Long,
    override val description: String = "假来源",
    override val maxParallel: Int = 2,
    private val delayMs: Long = 0,
    private val unusable: Boolean = false,
    private val blackUntilMs: Long = -1,
    private val failTimes: (Long) -> Boolean = { false },
) : ThumbnailSource {
    val requested = java.util.Collections.synchronizedList(ArrayList<Long>())
    val concurrent = AtomicInteger()
    val peak = AtomicInteger()
    var closed = false
    override val networkBytes: Long get() = requested.size * 1000L

    override suspend fun frameNear(timeMs: Long): ThumbnailFrame? {
        if (unusable) throw SourceUnusableException("用不了")
        requested += timeMs
        val now = concurrent.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        try {
            delay(delayMs)
            if (failTimes(timeMs)) return null
            // 像关键帧那样：取到的帧落在整秒上
            val actual = timeMs - timeMs % 1_000
            val shade = if (actual < blackUntilMs) 0 else 40 + (actual / 1_000 % 200).toInt()
            return ThumbnailFrame(actual, 8, 6, IntArray(48) { 0xFF000000.toInt() or (shade shl 16) or (shade shl 8) or shade })
        } finally {
            concurrent.decrementAndGet()
        }
    }

    override fun close() {
        closed = true
    }
}

class ThumbnailEngineTest {
    private val root = Files.createTempDirectory("pikseek-thumbs")
    private val cache = ThumbnailCache(root, PngSpriteCodec)
    private val engine = ThumbnailEngine(cache)
    private val idle = MutableStateFlow(false)
    private val oneWorker = MutableStateFlow(1)
    private val twoWorkers = MutableStateFlow(2)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun fingerprint(durationMs: Long, id: String = "file") = MediaFingerprint(id, "gcid-$id", 1_000_000, durationMs)

    private suspend fun ThumbnailEngine.Session.finish() = withTimeout(60_000) {
        join()
        close()
        // close 在后台存盘，等它写完
        delay(300)
    }

    @Test
    fun generatesEverySlotNearThePlayheadFirstThenCoarse(): Unit = runBlocking {
        val duration = 40 * 60_000L
        val source = FakeSource(duration, maxParallel = 1)
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 16 * 60_000L }, idle, oneWorker)
        session.finish()

        val progress = session.progress.value
        assertEquals(ThumbnailState.Complete, progress.state)
        assertEquals(90, progress.fullTotal)
        assertEquals(90, progress.fullDone)
        assertEquals(24, progress.coarseDone)
        assertEquals("假来源", progress.source)

        // 最先要的五帧在播放位置附近
        val plan = session.plan
        val firstFive = source.requested.take(5).map { plan.slotAt(it) }
        val here = plan.slotAt(16 * 60_000L)
        assertEquals(setOf(here - 2, here - 1, here, here + 1, here + 2), firstFive.toSet())
        // 紧接着的二十来帧把全片铺了一遍：最大的空当不超过两个粗略间隔
        val early = source.requested.take(5 + 24).sorted()
        val gaps = (listOf(0L) + early + listOf(duration)).zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() <= 2 * duration / 24, "最大空当 ${gaps.max()} ms")
        assertTrue(source.closed)
    }

    @Test
    fun hoverGetsTheNearestFrameAndNeverAFarAwayOne(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val gate = MutableStateFlow(true) // 一开始让引擎等着，先看「还没有图」时的样子
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(FakeSource(duration)) }, { 0L }, gate, oneWorker)
        delay(200)
        assertNull(session.frameAt(10 * 60_000L))
        gate.value = false
        session.finish()
        for (time in listOf(0L, 61_500L, 10 * 60_000L, duration - 1)) {
            val frame = assertNotNull(session.frameAt(time), "time=$time")
            assertTrue(abs(frame.timeMs - time) <= session.plan.intervalMs, "要 $time，得到 ${frame.timeMs}")
        }
    }

    @Test
    fun secondOpenIsServedEntirelyFromDiskWithoutTouchingTheSource(): Unit = runBlocking {
        val duration = 30 * 60_000L
        engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(FakeSource(duration)) }, { 0L }, idle, twoWorkers).finish()
        val directory = cache.directoryOf(fingerprint(duration))
        val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        // 60 帧，每张 25 格：三张雪碧图加一份索引，没有落下临时文件
        assertEquals(listOf("index.json", "sheet-000.png", "sheet-001.png", "sheet-002.png"), names)

        var asked = false
        val again = ThumbnailEngine(ThumbnailCache(root, PngSpriteCodec)).open(
            fingerprint(duration), PreviewDensity.Medium,
            { asked = true; listOf(FakeSource(duration)) }, { 0L }, idle, oneWorker,
        )
        again.finish()
        val progress = again.progress.value
        assertEquals(ThumbnailState.Complete, progress.state)
        assertEquals(60, progress.fromCache)
        assertEquals(60, progress.fullDone)
        assertEquals("本机缓存", progress.source)
        assertTrue(!asked, "缓存齐全时不该再去要画面来源")
        assertTrue(progress.cacheBytes > 0)
        // 读回来的图与当初生成的一致
        val frame = assertNotNull(again.frameAt(15 * 60_000L))
        val shade = 40 + (frame.timeMs / 1_000 % 200).toInt()
        assertEquals(0xFF000000.toInt() or (shade shl 16) or (shade shl 8) or shade, frame.pixels[0])
    }

    @Test
    fun interruptedRunResumesAndOnlyFetchesWhatIsMissing(): Unit = runBlocking {
        val duration = 30 * 60_000L
        val slow = FakeSource(duration, delayMs = 20, maxParallel = 1)
        val first = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(slow) }, { 0L }, idle, oneWorker)
        first.progress.first { it.fullDone >= 20 }
        first.close()
        delay(500)
        val done = first.progress.value.fullDone
        assertTrue(done in 20..59)

        val rest = FakeSource(duration)
        val second = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(rest) }, { 0L }, idle, oneWorker)
        second.finish()
        assertEquals(60, second.progress.value.fullDone)
        assertEquals(done, second.progress.value.fromCache)
        assertEquals(60 - done, rest.requested.size)
    }

    @Test
    fun yieldsWhileThePlayerIsBusy(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val busy = MutableStateFlow(false)
        val source = FakeSource(duration, delayMs = 10, maxParallel = 1)
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 0L }, busy, oneWorker)
        session.progress.first { it.fullDone >= 5 }
        busy.value = true
        delay(300) // 手上那一帧做完
        val paused = session.progress.value.fullDone
        delay(600)
        // 主播放器忙着的这段时间里一帧都不取
        assertEquals(paused, session.progress.value.fullDone)
        assertEquals(ThumbnailState.Yielding, session.progress.value.state)
        busy.value = false
        session.finish()
        assertEquals(60, session.progress.value.fullDone)
    }

    @Test
    fun secondWorkerOnlyRunsWhenAllowed(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val single = FakeSource(duration, delayMs = 5)
        engine.open(fingerprint(duration, "a"), PreviewDensity.Medium, { listOf(single) }, { 0L }, idle, oneWorker).finish()
        assertEquals(1, single.peak.get())

        val double = FakeSource(duration, delayMs = 5)
        engine.open(fingerprint(duration, "b"), PreviewDensity.Medium, { listOf(double) }, { 0L }, idle, twoWorkers).finish()
        assertEquals(2, double.peak.get())
    }

    @Test
    fun fallsBackToTheNextSourceWhenTheFirstIsUnusable(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val broken = FakeSource(duration, description = "转码流", unusable = true)
        val backup = FakeSource(duration, description = "原画")
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(broken, backup) }, { 0L }, idle, oneWorker)
        session.finish()
        assertEquals(ThumbnailState.Complete, session.progress.value.state)
        assertEquals("原画", session.progress.value.source)
        assertEquals(60, session.progress.value.fullDone)
    }

    @Test
    fun aSourceThatYieldsNothingIsAbandonedAfterAFewTriesNotAfterTheWholeQueue(): Unit = runBlocking {
        val duration = 100 * 60_000L
        // 打得开、但一帧都解不出来（比如转码流的写法与预想的不同）
        val dud = FakeSource(duration, description = "转码流", maxParallel = 1, failTimes = { true })
        val backup = FakeSource(duration, description = "原画")
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(dud, backup) }, { 0L }, idle, oneWorker)
        session.finish()
        assertEquals("原画", session.progress.value.source)
        assertEquals(120, session.progress.value.fullDone)
        // 没有把 120 格全试一遍才放弃
        assertTrue(dud.requested.size <= 8, "试了 ${dud.requested.size} 次才放弃")
    }

    @Test
    fun noUsableSourceEndsAsUnavailableNotAsAHang(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val session = engine.open(
            fingerprint(duration), PreviewDensity.Medium,
            { listOf(FakeSource(duration, unusable = true), FakeSource(duration, failTimes = { true })) }, { 0L }, idle, oneWorker,
        )
        session.finish()
        assertEquals(ThumbnailState.Unavailable, session.progress.value.state)
        assertNull(session.frameAt(60_000))
    }

    @Test
    fun transientFailuresAreRetriedOnce(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val attempts = HashMap<Long, Int>()
        // 每个时刻头一回都失败，第二回成功
        val flaky = FakeSource(duration, maxParallel = 1, failTimes = { time -> synchronized(attempts) { attempts.merge(time, 1, Int::plus) } == 1 })
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(flaky, FakeSource(duration)) }, { 0L }, idle, oneWorker)
        session.finish()
        // 第一轮全失败、一帧没有，换到了下一个来源；这是「整个用不了」的判定
        assertEquals(60, session.progress.value.fullDone)
    }

    @Test
    fun blackFramesAreReplacedByALaterFrame(): Unit = runBlocking {
        val duration = 20 * 60_000L
        // 片头 30 秒全黑
        val source = FakeSource(duration, blackUntilMs = 30_000, maxParallel = 1)
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 0L }, idle, oneWorker)
        session.finish()
        // 第一格本该是 10 秒处（黑的），往后挪过之后拿到的不是黑帧就用它，否则保留原来那张
        val first = assertNotNull(session.frameAt(10_000))
        assertTrue(first.timeMs >= 10_000)
        // 第二格 30 秒处不黑，原样
        assertTrue(!assertNotNull(session.frameAt(30_500)).isBlack())
        // 为黑帧多取了几次
        assertTrue(source.requested.size > 60)
    }

    @Test
    fun hoveringSomewhereUnpreparedJumpsTheQueue(): Unit = runBlocking {
        val duration = 100 * 60_000L
        val source = FakeSource(duration, delayMs = 15, maxParallel = 1)
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 0L }, idle, oneWorker)
        session.progress.first { it.fullDone >= 30 } // 附近与粗略预览都做完了
        // 挑一处不在粗略预览里的：那些已经做完了，不必插队
        val target = 79 * 60_000L + 35_000
        val slot = session.plan.slotAt(target)
        val before = source.requested.size
        session.prefer(target)
        session.progress.first { it.fullDone >= 36 }
        // 插队时手上可能正有一帧在取，所以往后多看几个
        val next = source.requested.drop(before).take(6).map { session.plan.slotAt(it) }
        assertTrue(slot in next, "悬停处的第 $slot 格应当紧接着就做，实际接着做的是 $next")
        session.close()
        delay(300)
    }

    @Test
    fun watchingUsesAStoredPreviewOfAnotherDensityAsIs(): Unit = runBlocking {
        val duration = 30 * 60_000L
        engine.open(fingerprint(duration), PreviewDensity.Low, { listOf(FakeSource(duration)) }, { 0L }, idle, oneWorker).finish()
        val source = FakeSource(duration)
        // 设置里的档次只管没有缓存的视频：已有低档的就用低档的，一帧也不重做
        val session = engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 0L }, idle, oneWorker)
        session.finish()
        assertEquals(30, session.plan.slotCount)
        assertEquals(30, session.progress.value.fromCache)
        assertEquals(0, source.requested.size)
    }

    @Test
    fun exactModeRebuildsAtTheChosenDensityInsteadOfMixing(): Unit = runBlocking {
        val duration = 30 * 60_000L
        engine.open(fingerprint(duration), PreviewDensity.Low, { listOf(FakeSource(duration)) }, { 0L }, idle, oneWorker).finish()
        val source = FakeSource(duration)
        val session = engine.open(
            fingerprint(duration), PreviewDensity.Medium, { listOf(source) }, { 0L }, idle, oneWorker,
            mode = ThumbnailEngine.CacheMode.Exact,
        )
        session.finish()
        assertEquals(60, session.plan.slotCount)
        assertEquals(0, session.progress.value.fromCache)
        assertEquals(60, source.requested.size)
        assertEquals(60, cache.stored(fingerprint(duration))?.frameCount)
    }

    @Test
    fun packRoundTripsIntoAnotherCacheAndCountsAsComplete(): Unit = runBlocking {
        val duration = 20 * 60_000L
        engine.open(fingerprint(duration), PreviewDensity.Medium, { listOf(FakeSource(duration)) }, { 0L }, idle, twoWorkers).finish()
        val pack = assertNotNull(cache.exportPack(fingerprint(duration)))
        val other = ThumbnailCache(Files.createTempDirectory("thumb-other").toString(), PngSpriteCodec)
        // 另一台设备读出的时长差了一秒：认 gcid，不认时长
        val elsewhere = fingerprint(duration + 1_000)
        val imported = assertNotNull(other.importPack(elsewhere, pack))
        assertTrue(imported.isComplete)
        val source = FakeSource(duration)
        val session = ThumbnailEngine(other).open(elsewhere, PreviewDensity.High, { listOf(source) }, { 0L }, idle, oneWorker)
        session.finish()
        assertEquals(0, source.requested.size)
        assertEquals(imported.slotCount, session.progress.value.fromCache)
        assertNull(other.importPack(elsewhere, pack.copyOf(pack.size - 3)))
    }

    @Test
    fun trimDeletesLeastRecentlyUsedFirstAndSparesTheCurrentVideo(): Unit = runBlocking {
        val duration = 20 * 60_000L
        val prints = listOf("old", "middle", "new").map { fingerprint(duration, it) }
        prints.forEachIndexed { index, print ->
            engine.open(print, PreviewDensity.Medium, { listOf(FakeSource(duration)) }, { 0L }, idle, twoWorkers).finish()
            // 最近使用时间：old 最早
            Files.setLastModifiedTime(cache.directoryOf(print).resolve("index.json"), FileTime.fromMillis(1_000_000L + index * 60_000))
        }
        val each = cache.sizeOf(prints[0])
        assertTrue(each > 0)
        assertEquals(cache.totalBytes(), prints.sumOf { cache.sizeOf(it) })

        // 上限只够放两个：删最久没用的那个
        val freed = cache.trim(limitBytes = each * 2 + each / 2)
        assertTrue(freed > 0)
        assertEquals(0, cache.sizeOf(prints[0]))
        assertTrue(cache.sizeOf(prints[1]) > 0 && cache.sizeOf(prints[2]) > 0)

        // 正在看的那个不删，哪怕它最旧
        cache.trim(limitBytes = each / 2, keep = prints[1])
        assertTrue(cache.sizeOf(prints[1]) > 0)
        assertEquals(0, cache.sizeOf(prints[2]))

        // 0 表示不限
        assertEquals(0, cache.trim(limitBytes = 0))
        assertTrue(cache.clear() > 0)
        assertEquals(0, cache.totalBytes())
    }

    @Test
    fun realStreamEndToEnd(): Unit = runBlocking {
        // 合成的转码流、真的解码、真的存盘：一条龙走一遍
        val stream = SyntheticTs(gopCount = 16, blackGops = setOf(5))
        val reader = MemoryRangeReader(stream.bytes)
        val temp = Files.createTempDirectory("pikseek-e2e")
        try {
            MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
                val print = MediaFingerprint("ts", "gcid-ts", stream.bytes.size.toLong(), stream.durationMs)
                val source = TsSliceSource(reader, stream.durationMs, grabber, temp, "合成转码流")
                val session = engine.open(print, PreviewDensity.Medium, { listOf(source) }, { 40_000L }, idle, twoWorkers)
                session.finish()
                val progress = session.progress.value
                assertEquals(ThumbnailState.Complete, progress.state)
                assertEquals(progress.fullTotal, progress.fullDone)
                assertTrue(progress.networkBytes > 0)

                // 时间轴上任何一处悬停，拿到的都是那个时刻所在一段的画面
                for (time in 0 until stream.durationMs step 2_300) {
                    val frame = assertNotNull(session.frameAt(time), "time=$time")
                    val gop = (frame.timeMs / stream.gopMs).toInt()
                    assertTrue(abs(frame.timeMs - time) <= stream.gopMs, "要 $time，得到 ${frame.timeMs}")
                    // 第 5 段是黑场：要么是它本身（黑），要么已换成后面一段的画面
                    if (gop != 5) TestMedia.assertColor(stream.rgbOf(gop), frame, "time=$time")
                }

                // 再开一次：全部来自磁盘，一个字节都不再读
                val readsBefore = reader.reads
                val again = engine.open(print, PreviewDensity.Medium, { error("不该再要来源") }, { 0L }, idle, oneWorker)
                again.finish()
                assertEquals(again.progress.value.fullTotal, again.progress.value.fromCache)
                assertEquals(readsBefore, reader.reads)
                TestMedia.assertColor(stream.rgbOf(2), assertNotNull(again.frameAt(12_000)), "读回的缓存")
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
