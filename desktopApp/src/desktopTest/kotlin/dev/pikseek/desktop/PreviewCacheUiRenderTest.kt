package dev.pikseek.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.ui.LocalPreviewPacks
import dev.pikseek.ui.PreviewPackControl
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewCacheBadge
import dev.pikseek.ui.preview.PreviewCacheDialog
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewCacheTarget
import dev.pikseek.ui.preview.PreviewJobsState
import io.github.nihildigit.pikpak.FileStat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat

/**
 * 把「预览缓存」的弹窗与角标离屏画成图片（不开窗口），存到 PIKSEEK_RENDER_DIR（没设时不存），给人看一眼。
 * 断言只查画得出来。
 */
class PreviewCacheUiRenderTest {
    private val gcids = listOf("A", "B", "C").map { it.repeat(40) }

    private val control = object : PreviewPackControl {
        override val packs = MutableStateFlow(
            mapOf(
                gcids[0] to CloudPack("1", PreviewPackName(gcids[0], PreviewDensity.Medium, 120, 120)),
                gcids[1] to CloudPack("2", PreviewPackName(gcids[1], PreviewDensity.Low, 22, 60)),
            ),
        )
        override val live = MutableStateFlow(mapOf(gcids[2] to 0.37f))
        override val jobs = MutableStateFlow(
            PreviewJobsState(running = true, label = "文件夹「番剧」及子文件夹", total = 24, made = 6, skipped = 3, current = "第 10 话.mkv", currentFraction = 0.37f, queued = 1),
        )
        override fun refresh() = Unit
        override fun start(request: PreviewCacheRequest) = Unit
        override fun cancel() = Unit
    }

    @Test
    fun dialogAndBadgesRender() {
        val out = System.getenv("PIKSEEK_RENDER_DIR")?.let(::File)?.also { it.mkdirs() }
        render(420, 760, out?.resolve("preview_cache_dialog.png")) {
            PreviewCacheDialog(PreviewCacheTarget.Folder("f", "番剧"), onDismiss = {})
        }
        render(420, 140, out?.resolve("preview_cache_badges.png")) {
            Row(Modifier.padding(12.dp)) {
                gcids.forEachIndexed { index, gcid ->
                    Box(Modifier.padding(end = 12.dp).size(110.dp, 70.dp).background(Color(0xFF3A4A5A))) {
                        PreviewCacheBadge(
                            FileStat(kind = "drive#file", id = "$index", name = "v$index.mp4", size = "1", hash = gcid),
                            Modifier.align(Alignment.TopEnd).padding(4.dp),
                        )
                    }
                }
            }
        }
    }

    private fun render(width: Int, height: Int, file: File?, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(width * 2, height * 2, Density(2f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalPreviewPacks provides control) {
                    Surface(Modifier.fillMaxSize()) { Column { content() } }
                }
            }
        }.use { scene ->
            scene.render(0)
            val image = scene.render(1_000_000_000L)
            val data = image.encodeToData(EncodedImageFormat.PNG)!!
            assertTrue(data.size > 1000)
            file?.writeBytes(data.bytes)
        }
    }
}
