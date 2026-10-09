package dev.pikseek.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.state.FileRating
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.pikseek.platform.DragSeekMode
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.RatingBook
import dev.pikseek.ui.player.LocalTimelinePreview
import dev.pikseek.ui.player.TimelinePreview
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewPackStore
import dev.pikseek.ui.rating.FileRatings
import dev.pikseek.ui.rating.LocalFileRatings
import dev.pikseek.ui.rating.RatingButton
import dev.pikseek.ui.rating.RatingFilterBar
import io.github.nihildigit.pikpak.FileStat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** 收藏按钮：单击、双击、取消；封面上三种样子、筛选条、播放器顶栏里的按钮。不开窗口，截图存到 shots。 */
@OptIn(ExperimentalTestApi::class)
class RatingUiTest {
    private val shots = File(System.getProperty("pikseek.shots.dir")).apply { mkdirs() }
    private val files = (1..3).map { FileStat(kind = "drive#file", id = "v$it", name = "第0${it}话.mp4", size = "1000000", hash = "$it".repeat(40)) }

    private fun ratings() = FileRatings(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        store = NoStore,
        setStarred = { _, _ -> Result.success(Unit) },
        accounts = MutableStateFlow(null),
    )

    @Test
    fun `click likes, double click dislikes, click again clears`() = runComposeUiTest {
        val ratings = ratings()
        val file = files[0]
        setContent { Themed { CompositionLocalProvider(LocalFileRatings provides ratings) { Cover { RatingButton(file) } } } }
        onNodeWithContentDescription("未标记", substring = true).performClick()
        waitForIdle()
        assertEquals(FileRating.LIKED, ratings.ratingOf(file))
        onNodeWithContentDescription("收藏", substring = true).performClick()
        waitForIdle()
        assertEquals(FileRating.NONE, ratings.ratingOf(file))
        onNodeWithContentDescription("未标记", substring = true).performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(FileRating.DISLIKED, ratings.ratingOf(file))
        onNodeWithContentDescription("讨厌", substring = true).performClick()
        waitForIdle()
        assertEquals(FileRating.NONE, ratings.ratingOf(file))
    }

    @Test
    fun `three looks on covers and the filter bar`() = runComposeUiTest {
        val ratings = ratings()
        ratings.set(files[1], FileRating.LIKED)
        ratings.set(files[2], FileRating.DISLIKED)
        var filter by mutableStateOf<FileRating?>(null)
        setContent {
            Themed {
                CompositionLocalProvider(LocalFileRatings provides ratings) {
                    Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RatingFilterBar(filter, mapOf(FileRating.LIKED to 12, FileRating.DISLIKED to 3, FileRating.NONE to 41), { filter = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            files.forEach { file -> Cover { RatingButton(file, Modifier.padding(5.dp)) } }
                        }
                    }
                }
            }
        }
        waitForIdle()
        save("rating-covers.png")
        onNodeWithContentDescription("只看讨厌", substring = true).performClick()
        waitForIdle()
        assertEquals(FileRating.DISLIKED, filter)
        save("rating-filter-disliked.png")
    }

    @Test
    fun `the player top bar has the button`() = runComposeUiTest {
        val ratings = ratings()
        val preview = TimelinePreview(dragSeekMode = { DragSeekMode.Off }, seek = {}).apply { ratingFile = files[0] }
        setContent {
            Themed {
                CompositionLocalProvider(LocalFileRatings provides ratings, LocalTimelinePreview provides preview) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                        MobilePlayerControls(
                            title = "第01话.mp4",
                            isLocalPlayback = false,
                            isPlaying = false,
                            isLoading = false,
                            positionMillis = 60_000,
                            durationMillis = 1_440_000,
                            bufferedPositionMillis = 120_000,
                            playbackSpeed = 1f,
                            aspectRatio = PlayerAspectRatio.Fit,
                            qualityOptions = emptyList(),
                            currentQuality = null,
                            errorMessage = null,
                            resumedFromMillis = null,
                            onPlayPause = {},
                            onSeek = {},
                            onSpeedChange = {},
                            onAspectRatioChange = {},
                            onQualityChange = {},
                            onRetry = {},
                            onRestartFromBeginning = {},
                            onBack = {},
                            onToggleFullscreen = {},
                        )
                    }
                }
            }
        }
        onAllNodesWithContentDescription("单击收藏", substring = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        onNodeWithContentDescription("单击收藏", substring = true).performClick()
        waitForIdle()
        assertEquals(FileRating.LIKED, ratings.ratingOf(files[0]))
        save("rating-player.png")
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = MaterialTheme(colorScheme = darkColorScheme(), content = content)

    /** 一张假封面：深色渐变，像视频截图。 */
    @Composable
    private fun Cover(content: @Composable () -> Unit) {
        Box(
            Modifier.size(200.dp, 112.dp).background(Brush.linearGradient(listOf(Color(0xFF6B4F5A), Color(0xFF2B3440), Color(0xFFD9B8A0)))),
            contentAlignment = Alignment.TopEnd,
        ) { content() }
    }

    private fun ComposeUiTest.save(name: String) {
        val layers = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it].captureToImage().asSkiaBitmap() }
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(layers[0].width, layers[0].height)
        layers.forEach { surface.canvas.drawImage(Image.makeFromBitmap(it), 0f, 0f) }
        File(shots, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private object NoStore : PreviewPackStore {
        override val packs: StateFlow<Map<String, CloudPack>> = MutableStateFlow(emptyMap())
        override suspend fun refresh(force: Boolean) = Unit
        override suspend fun lookup(gcid: String): CloudPack? = null
        override suspend fun download(pack: CloudPack): ByteArray = error("不用")
        override suspend fun upload(name: PreviewPackName, bytes: ByteArray): CloudPack = error("不用")
        override suspend fun loadMarks(gcid: String): MediaMarks? = null
        override suspend fun saveMarks(marks: MediaMarks) = Unit
        override suspend fun loadRatings(fresh: Boolean): RatingBook = RatingBook()
        override suspend fun saveRatings(book: RatingBook) = Unit
    }
}
