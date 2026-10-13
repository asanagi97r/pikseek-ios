package dev.pikseek.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.pikseek.ui.sort.LocalPlaylistSort
import dev.pikseek.ui.sort.PlaylistSort
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** 选集面板顶上的排序按钮：显示这个文件夹眼下的排序，改了交给窗口去记、去重排。 */
@OptIn(ExperimentalTestApi::class)
class PlaylistSortUiTest {
    private val shots = File(System.getProperty("pikseek.shots.dir")).apply { mkdirs() }
    private val playlist = (1..4).map { PlaylistEntry(fileId = "v$it", name = "第0${it}话.mp4", label = "0$it") }

    @Test
    fun `the episode panel shows the folder sort and changes it`() = runComposeUiTest {
        val sort = PlaylistSort().apply { order = PikoFileSortOrder.TIME_DESC }
        val chosen = ArrayList<PikoFileSortOrder>()
        sort.onChange = { chosen += it; sort.order = it }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPlaylistSort provides sort) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                        MobilePlayerControls(
                            title = "第01话.mp4", isLocalPlayback = false, isPlaying = false, isLoading = false,
                            positionMillis = 60_000, durationMillis = 1_440_000, bufferedPositionMillis = 120_000,
                            playbackSpeed = 1f, aspectRatio = PlayerAspectRatio.Fit, qualityOptions = emptyList(),
                            currentQuality = null, errorMessage = null, resumedFromMillis = null,
                            onPlayPause = {}, onSeek = {}, onSpeedChange = {}, onAspectRatioChange = {}, onQualityChange = {},
                            onRetry = {}, onRestartFromBeginning = {}, onBack = {}, onToggleFullscreen = {},
                            playlist = playlist, currentFileId = "v1", hasNext = true,
                        )
                    }
                }
            }
        }
        // 宽窗口里选集按钮带字
        onNodeWithText("选集").performClick()
        waitForIdle()
        onNodeWithText("按创建时间").assertExists()
        save("playlist-sort.png")
        onNodeWithText("按创建时间").performClick()
        waitForIdle()
        onNodeWithText("名称").performClick()
        waitForIdle()
        assertEquals(listOf(PikoFileSortOrder.NAME_ASC), chosen)
        onNodeWithText("按名称").assertExists()
    }

    private fun ComposeUiTest.save(name: String) {
        val layers = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it].captureToImage().asSkiaBitmap() }
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(layers[0].width, layers[0].height)
        layers.forEach { surface.canvas.drawImage(Image.makeFromBitmap(it), 0f, 0f) }
        File(shots, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
