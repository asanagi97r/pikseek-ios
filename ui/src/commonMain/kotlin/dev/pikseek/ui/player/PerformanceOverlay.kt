package dev.pikseek.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pikseek.performance.PerformanceMetrics

/**
 * 性能浮层：起播、拖动、换集各用了多久，预览做到哪了。数字都来自 [PerformanceMetrics]，只在本机内存里。
 * 在设置的「播放预览」里打开；平时不显示。
 */
@Composable
fun PerformanceOverlay(modifier: Modifier = Modifier) {
    val metrics by PerformanceMetrics.state.collectAsState()
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Line("Media API", metrics.mediaApiMillis.ms())
            Line("Media URL", metrics.mediaUrlMillis.ms())
            Line("MPV load", metrics.mpvLoadMillis.ms())
            Line("First frame", metrics.firstFrameMillis.ms())
            Line("Seek", metrics.seekMillis.ms())
            Line("Switch", metrics.switchMillis.ms())
            Line("Next ready", if (metrics.nextPrefetched) "yes" else "no")
            Line("Thumb coarse", "${metrics.thumbnailCoarseDone} / ${metrics.thumbnailCoarseTotal}")
            Line("Thumb full", "${metrics.thumbnailFullDone} / ${metrics.thumbnailFullTotal}")
            Line("Thumb state", metrics.thumbnailState.ifEmpty { "—" })
            Line("Thumb net", if (metrics.thumbnailNetworkBytes < 0) "—" else megabytes(metrics.thumbnailNetworkBytes))
            Line("Preview cache", megabytes(metrics.previewCacheBytes))
            Line("CDN", metrics.cdnHost ?: "—")
            Line("Dropped", metrics.droppedFrames?.toString() ?: "—")
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row {
        Text(name, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

private fun Long?.ms(): String = this?.let { "$it ms" } ?: "—"

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
