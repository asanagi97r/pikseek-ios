package dev.pikseek.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.pikseek.platform.DragSeekMode
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.ui.player.LocalTimelinePreview
import dev.pikseek.ui.player.TimelinePreview
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** 进度条分段：刻度、拖动吸住、气泡里的段号、右键改分点、跳过片头。真的播放器控件，不开窗口。 */
@OptIn(ExperimentalTestApi::class)
class TimelineMarksUiTest {
    private val shots = File(System.getProperty("pikseek.shots.dir")).apply { mkdirs() }
    private val duration = 1_440_000L
    private val marks = MediaMarks(
        gcid = "AB".repeat(20),
        durationMs = duration,
        scenes = listOf(600_000, 900_000),
        scenesDone = true,
        intro = MediaMarks.Span(30_000, 120_000),
        outro = MediaMarks.Span(1_320_000, 1_410_000),
        episodeDone = true,
    )

    private fun preview(saved: MutableList<MediaMarks> = ArrayList()) = TimelinePreview(dragSeekMode = { DragSeekMode.Off }, seek = {}).apply {
        marks = this@TimelineMarksUiTest.marks
        saveMarks = { saved += it }
    }

    @Test
    fun `ticks show, the bubble names the segment and dragging snaps onto a cut`() = runComposeUiTest {
        val preview = preview()
        var committed: Long? = null
        setContent { host(preview, position = 300_000, onSeek = { committed = it }) }
        // 悬停在第二段里
        onNodeWithContentDescription("播放进度").performMouseInput { moveTo(at(0.5f)) }
        waitForIdle()
        onNodeWithText("第 2 段 · 共 3 段").assertExists()
        save("marks-hover.png")

        // 拖到第一个分点（10 分钟，0.4167）旁边一点点再松手：吸到分点上
        onNodeWithContentDescription("播放进度").performMouseInput {
            moveTo(at(0.2f))
            press()
            moveTo(at(0.4167f + 0.004f))
        }
        waitForIdle()
        save("marks-snap.png")
        onNodeWithContentDescription("播放进度").performMouseInput { release() }
        waitForIdle()
        assertEquals(600_000L, committed, "该吸到 10:00 的分点上")
    }

    @Test
    fun `right click adds a cut and saves it`() = runComposeUiTest {
        val saved = ArrayList<MediaMarks>()
        val preview = preview(saved)
        var committed: Long? = null
        setContent { host(preview, position = 300_000, onSeek = { committed = it }) }
        onNodeWithContentDescription("播放进度").performMouseInput {
            moveTo(at(0.25f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
        save("marks-menu.png")
        onNodeWithText("加分点", substring = true).performClick()
        waitForIdle()
        assertEquals(null, committed, "右键不该跳")
        val last = assertNotNull(saved.lastOrNull(), "改了要存")
        // 点在四分之一处附近（两端各让出手柄的半径），取整到秒
        assertTrue(last.scenes.any { kotlin.math.abs(it - 360_000) <= 10_000 && it % 1000 == 0L }, "${last.scenes}")
        assertTrue(last.scenesEdited)
        assertEquals(last, preview.marks)
    }

    @Test
    fun `inside the intro a skip button jumps to its end`() = runComposeUiTest {
        var committed: Long? = null
        setContent { host(preview(), position = 60_000, onSeek = { committed = it }) }
        waitForIdle()
        save("marks-skip-intro.png")
        onNodeWithText("跳过片头").performClick()
        waitForIdle()
        assertEquals(120_000L, committed)
    }

    @Test
    fun `inside the outro the button goes to the next episode`() = runComposeUiTest {
        var next = 0
        setContent { host(preview(), position = 1_330_000, onSeek = {}, hasNext = true, onNext = { next++ }) }
        onNodeWithText("跳过片尾 · 下一集").performClick()
        waitForIdle()
        assertEquals(1, next)
    }

    @Test
    fun `auto skip jumps without a click`() = runComposeUiTest {
        val preview = preview().apply { autoSkip = { true } }
        var committed: Long? = null
        setContent { host(preview, position = 40_000, onSeek = { committed = it }) }
        waitForIdle()
        assertEquals(120_000L, committed)
    }

    @Test
    fun `the settings panel lets touch users edit at the current position`() = runComposeUiTest {
        val saved = ArrayList<MediaMarks>()
        setContent { host(preview(saved), position = 300_000, onSeek = {}) }
        onNodeWithContentDescription("播放设置").performClick()
        waitForIdle()
        onNodeWithText("进度条分段").assertExists()
        save("marks-settings.png")
        onNodeWithText("片头到 5:00 结束").performClick()
        waitForIdle()
        assertEquals(MediaMarks.Span(30_000, 300_000), saved.last().intro)
    }

    private fun androidx.compose.ui.test.MouseInjectionScope.at(fraction: Float) = centerLeft + (centerRight - centerLeft) * fraction

    @Composable
    private fun host(preview: TimelinePreview, position: Long, onSeek: (Long) -> Unit, hasNext: Boolean = false, onNext: () -> Unit = {}) {
        CompositionLocalProvider(LocalTimelinePreview provides preview) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                MobilePlayerControls(
                    title = "测试剧 第01话.mkv",
                    isLocalPlayback = false,
                    isPlaying = false,
                    isLoading = false,
                    positionMillis = position,
                    durationMillis = duration,
                    bufferedPositionMillis = position + 60_000,
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
                    hasNext = hasNext,
                    onNext = onNext,
                    seekThumbOnHoverOnly = true,
                )
            }
        }
    }

    /** 把眼下的界面存成 PNG。弹出的菜单是另一层，叠在主界面上一起存。 */
    private fun ComposeUiTest.save(name: String) {
        val layers = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it].captureToImage().asSkiaBitmap() }
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(layers[0].width, layers[0].height)
        layers.forEach { surface.canvas.drawImage(Image.makeFromBitmap(it), 0f, 0f) }
        File(shots, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
