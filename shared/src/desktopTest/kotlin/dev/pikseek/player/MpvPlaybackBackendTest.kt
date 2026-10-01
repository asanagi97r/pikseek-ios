package dev.pikseek.player

import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * iOS 上用的播放后端，在桌面上对着真的 libmpv 跑：打开、第一帧、放完、拖动、出错、换文件。
 * 没有画面输出（vo=null），验的是命令与事件的逻辑，不是渲染。
 */
class MpvPlaybackBackendTest {
    private val mpvDirectory = Path.of(System.getProperty("pikseek.mpv.dir"))
    private val media = Path.of(System.getProperty("pikseek.testdata"))
    private val mp4 = media.resolve("control-mpeg4-aac.mp4").toString()
    private val wmv = media.resolve("wmv2-wmav2.wmv").toString()

    /** 一条专用线程顶替界面线程；后端的状态只在它上面读写。 */
    private class Rig(mpvDirectory: Path) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-main").apply { isDaemon = true } }
        val main = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val backend = MpvPlaybackBackend(FfmMpvHandle(mpvDirectory), scope)
        val events = CopyOnWriteArrayList<PlaybackBackendEvent>()

        init {
            scope.launch { backend.events.collect { events += it } }
        }

        suspend fun <T> onMain(block: suspend () -> T): T = withContext(main) { block() }

        suspend fun awaitEvent(description: String, timeoutMs: Long = 15_000, match: (PlaybackBackendEvent) -> Boolean) {
            withTimeoutOrNull(timeoutMs) {
                while (events.none(match)) delay(20)
            } ?: error("没等到：$description；收到的事件 $events")
        }

        suspend fun awaitState(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
            withTimeoutOrNull(timeoutMs) {
                while (!onMain { condition() }) delay(20)
            } ?: error("没等到：$description")
        }

        override fun close() {
            runBlocking { onMain { backend.close() } }
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun opensPlaysToTheEndAndReportsReadyThenEnded(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(mp4), 0) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
            rig.awaitState("读到时长") { rig.backend.durationMillis in 3_500..4_500 }
            assertTrue(rig.onMain { rig.backend.isPlaying })
            rig.awaitState("位置在走") { rig.backend.positionMillis > 500 }
            rig.awaitState("第一帧之后不再算缓冲") { !rig.backend.isBuffering }
            assertEquals(1, rig.onMain { rig.backend.audioTracks.size })
            assertNotNull(rig.onMain { rig.backend.selectedAudioTrackId })
            rig.awaitState("画面比例") { rig.backend.videoAspect != null }
            assertEquals(176f / 144f, rig.onMain { rig.backend.videoAspect }!!, 0.01f)
            rig.awaitEvent("放完") { it == PlaybackBackendEvent.Ended }
            // Ready 在 Ended 之前，而且各只有一次
            assertEquals(listOf(PlaybackBackendEvent.Ready, PlaybackBackendEvent.Ended), rig.events.toList())

            // 停在末尾时还能往回拖，再播
            rig.onMain { rig.backend.seekTo(1_000) }
            assertEquals(1_000, rig.onMain { rig.backend.positionMillis }, "拖动后位置立刻是目标")
            rig.awaitState("拖回去之后接着走") { rig.backend.positionMillis in 1_200..3_500 }
        }
    }

    @Test
    fun opensPausedAtAStartPosition(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(mp4), 2_000, playWhenReady = false) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
            assertFalse(rig.onMain { rig.backend.isPlaying })
            delay(600)
            val position = rig.onMain { rig.backend.positionMillis }
            assertTrue(position in 1_000..2_600, "停在起点附近，实际 $position")
            assertTrue(rig.events.none { it == PlaybackBackendEvent.Ended })

            rig.onMain { rig.backend.play() }
            rig.awaitState("开始播") { rig.backend.isPlaying && rig.backend.positionMillis > position + 300 }
            rig.onMain { rig.backend.pause() }
            rig.awaitState("暂停") { !rig.backend.isPlaying }
        }
    }

    @Test
    fun playsAFormatTheSystemPlayerCannot(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(wmv), 0) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
            rig.awaitState("位置在走") { rig.backend.positionMillis > 500 }
        }
    }

    @Test
    fun aFileThatCannotBeOpenedFailsTheOpenCall(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            assertFailsWith<IllegalStateException> {
                withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(media.resolve("no-such-file.mkv").toString()), 0) } }
            }
            rig.awaitState("失败后不算在缓冲") { !rig.backend.isBuffering }
            assertTrue(rig.events.isEmpty(), "打不开是 open 抛异常，不另发事件：${rig.events}")

            // 之后照样能开别的
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(mp4), 0) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
        }
    }

    @Test
    fun switchingFilesDoesNotReportTheOldOneAsEndedOrFailed(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(mp4), 0) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
            rig.events.clear()
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(wmv), 0) } }
            rig.awaitEvent("第二个文件的第一帧") { it == PlaybackBackendEvent.Ready }
            assertEquals(listOf<PlaybackBackendEvent>(PlaybackBackendEvent.Ready), rig.events.toList())
            rig.awaitState("换了文件的时长") { rig.backend.durationMillis in 3_500..4_500 }
        }
    }

    @Test
    fun speedVolumeAspectAndStop(): Unit = runBlocking {
        Rig(mpvDirectory).use { rig ->
            withTimeout(15_000) { rig.onMain { rig.backend.open(PlaybackTarget.LocalFile(mp4), 0, playWhenReady = false) } }
            rig.awaitEvent("第一帧") { it == PlaybackBackendEvent.Ready }
            rig.onMain {
                rig.backend.setSpeed(2f)
                rig.backend.setVolume(0.4f)
                rig.backend.setAspectRatio(PlayerAspectRatio.Crop)
            }
            rig.awaitState("倍速生效") { rig.backend.speed == 2f }
            assertEquals(0.4f, rig.onMain { rig.backend.volume }!!, 0.01f)
            assertEquals(PlayerAspectRatio.Crop, rig.onMain { rig.backend.aspectRatio })

            rig.onMain { rig.backend.setRotation(90) }
            rig.awaitState("转了 90 度后宽高对调") { rig.backend.videoAspect?.let { it < 1f } == true }

            rig.events.clear()
            rig.onMain { rig.backend.stop() }
            delay(500)
            assertTrue(rig.events.isEmpty(), "主动停下不发事件：${rig.events}")
        }
    }
}
