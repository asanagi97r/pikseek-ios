package dev.pikseek.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.pikseek.platform.DragSeekMode
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.MpvFrameGrabber
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.directoryOf
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailState
import dev.pikseek.ui.player.LocalTimelinePreview
import dev.pikseek.ui.player.TimelinePreview
import dev.pikseek.ui.player.WebpSpriteCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
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
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 时间轴预览从头到尾走一遍，用的都是正式程序里的那一套：真的 libmpv 解码仓库里的样片，
 * Skia 的 WebP 存盘，真的播放器控件，真的鼠标悬停事件。
 */
@OptIn(ExperimentalTestApi::class)
class TimelinePreviewUiTest {
    private val mpvDirectory = Path.of(System.getProperty("pikseek.mpv.dir"))
    private val sample = File(System.getProperty("piko.testdata"), "control-mpeg4-aac.mp4")
    private val root = Files.createTempDirectory("pikseek-ui-thumbs")
    private val shots = File(System.getProperty("pikseek.shots.dir")).apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** 用样片把预览做完，返回会话。样片只有 4 秒，所以把它当成一个 4 秒的视频来做。 */
    private fun finishedSession(): ThumbnailEngine.Session = runBlocking {
        val engine = ThumbnailEngine(ThumbnailCache(root, WebpSpriteCodec))
        val grabber = MpvFrameGrabber(mpvDirectory)
        val session = engine.open(
            fingerprint = MediaFingerprint("sample", "", sample.length(), 4_000),
            density = PreviewDensity.Medium,
            sources = { listOf(SeekingSource(sample.absolutePath, grabber, "本机文件")) },
            position = { 0L },
            busy = MutableStateFlow(false),
            parallelism = MutableStateFlow(1),
        )
        withTimeout(60_000) { session.progress.first { it.state == ThumbnailState.Complete || it.state == ThumbnailState.Unavailable } }
        assertEquals(ThumbnailState.Complete, session.progress.value.state)
        session.close()
        delay(500)
        grabber.close()
        session
    }

    @Test
    fun `webp sprites survive a round trip through disk`() {
        val session = finishedSession()
        val directory = ThumbnailCache(root, WebpSpriteCodec).directoryOf(session.fingerprint)
        val names = directory.toFile().list()!!.sorted()
        assertEquals(listOf("index.json", "sheet-000.webp"), names)
        // 确实是 WebP：RIFF....WEBP
        val head = directory.resolve("sheet-000.webp").toFile().readBytes().copyOf(12)
        assertEquals("RIFF", String(head, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(head, 8, 4, Charsets.US_ASCII))

        // 再开一次：全部来自磁盘，画面与当初生成的差不多（WebP 有损，容许一点偏差）
        val again = runBlocking {
            val engine = ThumbnailEngine(ThumbnailCache(root, WebpSpriteCodec))
            engine.open(session.fingerprint, PreviewDensity.Medium, { error("缓存齐全时不该要来源") }, { 0L }, MutableStateFlow(false), MutableStateFlow(1))
                .also { opened -> withTimeout(30_000) { opened.progress.first { it.state == ThumbnailState.Complete } } }
        }
        assertEquals(again.progress.value.fullTotal, again.progress.value.fromCache)
        val original = assertNotNull(session.frameAt(2_000))
        val restored = assertNotNull(again.frameAt(2_000))
        assertEquals(original.width, restored.width)
        assertEquals(original.height, restored.height)
        var difference = 0L
        for (index in original.pixels.indices) {
            val a = original.pixels[index]
            val b = restored.pixels[index]
            difference += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
        }
        val meanError = difference.toDouble() / (original.pixels.size * 3)
        assertTrue(meanError < 10.0, "平均每通道偏差 $meanError")
        again.close()
    }

    @Test
    fun `hovering the seek bar shows the frame for that moment`() = runComposeUiTest {
        val session = finishedSession()
        val seeks = ArrayList<Long>()
        val preview = TimelinePreview(dragSeekMode = { DragSeekMode.Always }, seek = { seeks += it })
        preview.session = session
        preview.progress = session.progress.value
        // 悬停处确实有图，而且立刻就有：不等、不发请求
        assertNotNull(preview.imageAt(2_000))

        var committed: Long? = null
        setContent {
            CompositionLocalProvider(LocalTimelinePreview provides preview) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                    controls(onSeek = { committed = it })
                }
            }
        }
        onNodeWithContentDescription("播放进度").performMouseInput { moveTo(center) }
        waitForIdle()
        // 时间还在，上面多了一张图
        onNodeWithText("00:02", substring = true).assertExists()
        save("timeline-hover.png")
        assertTrue(seeks.isEmpty(), "悬停只看图，不该让主画面跳")
        assertNull(committed)

        // 拖动：按下、拖到四分之三处、松手。「始终跟随」时拖动中主画面就在跳，松手再跳最后一次
        onNodeWithContentDescription("播放进度").performMouseInput {
            press()
            moveTo(centerLeft + (centerRight - centerLeft) * 0.75f)
        }
        waitForIdle()
        save("timeline-drag.png")
        assertTrue(seeks.isNotEmpty(), "拖动中应当跟着跳")
        assertNull(committed, "还没松手，不该提交")
        onNodeWithContentDescription("播放进度").performMouseInput { release() }
        waitForIdle()
        val target = assertNotNull(committed)
        assertTrue(target in 2_700..3_300, "松手处该是四分之三，实际 $target")
    }

    @Test
    fun `dragging with seek-while-dragging off only seeks on release`() = runComposeUiTest {
        val seeks = ArrayList<Long>()
        val preview = TimelinePreview(dragSeekMode = { DragSeekMode.Off }, seek = { seeks += it })
        var committed: Long? = null
        setContent {
            CompositionLocalProvider(LocalTimelinePreview provides preview) { controls(onSeek = { committed = it }) }
        }
        onNodeWithContentDescription("播放进度").performMouseInput {
            // 先把指针移到进度条上再按下：按下发生在指针所在处
            moveTo(centerLeft + (centerRight - centerLeft) * 0.25f)
            press()
            // 落在 2.4 秒处：离整秒远一点，浮点取整不会让显示的秒数跳来跳去
            moveTo(centerLeft + (centerRight - centerLeft) * 0.6f)
        }
        waitForIdle()
        assertTrue(seeks.isEmpty())
        assertNull(committed)
        // 没有会话（预览关着、或还没出第一帧）时气泡里只有时间，照常能用。拖动中底栏的当前时间也跟着显示目标处
        assertTrue(onAllNodesWithText("00:02", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithContentDescription("播放进度").performMouseInput { release() }
        waitForIdle()
        assertNotNull(committed)
        assertTrue(seeks.isEmpty())
    }

    @androidx.compose.runtime.Composable
    private fun controls(onSeek: (Long) -> Unit) {
        MobilePlayerControls(
            title = "测试视频.mkv",
            isLocalPlayback = false,
            isPlaying = false,
            isLoading = false,
            positionMillis = 500L,
            durationMillis = 4_000L,
            bufferedPositionMillis = 1_500L,
            playbackSpeed = 1f,
            aspectRatio = PlayerAspectRatio.Fit,
            qualityOptions = emptyList(),
            currentQuality = null,
            errorMessage = null,
            resumedFromMillis = null,
            onPlayPause = {},
            onSeek = onSeek,
            onSpeedChange = {},
            onAspectRatioChange = {},
            onQualityChange = {},
            onRetry = {},
            onRestartFromBeginning = {},
            onBack = {},
            onToggleFullscreen = {},
            seekThumbOnHoverOnly = true,
        )
    }

    /** 把眼下的界面存成 PNG，留在构建目录里供人看一眼。 */
    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(shots, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
