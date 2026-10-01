package dev.pikseek.ui.player

import dev.piko.ui.platform.monotonicMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pikseek.platform.DragSeekMode
import dev.pikseek.player.DragSeekPolicy
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.ThumbnailProgress
import dev.pikseek.thumbnail.ThumbnailState

/**
 * 进度条悬停与拖动时要的东西：某个时刻的预览图，以及拖动中要不要让主画面跟着走。
 * 播放窗口提供它，进度条经 [LocalTimelinePreview] 取用；没有提供时进度条照旧只显示时间。
 */
class TimelinePreview(
    private val dragSeekMode: () -> DragSeekMode,
    private val seek: (Long) -> Unit,
) {
    /** 当前视频的缩略图会话；还没开始（等第一帧）或已关掉预览时为 null。 */
    var session by mutableStateOf<ThumbnailEngine.Session?>(null)

    /** 引擎的进度，界面读它来刷新悬停处的图与「生成中」的提示。 */
    var progress by mutableStateOf<ThumbnailProgress?>(null)

    // 帧转成位图要拷一次像素，悬停时来回扫会反复用到同几张，留一小批
    // 按最近用到的先后排着：用到的挪到末尾，满了从头上丢
    private val bitmaps = LinkedHashMap<ThumbnailFrame, ImageBitmap>()
    private val dragPolicy = DragSeekPolicy()

    /**
     * [timeMs] 处的预览图，附近还没有图时为 null。只读内存，立刻返回，不会触发任何网络请求。
     * 顺带告诉引擎用户在看这里：那里没有图的话，它会把那几格提到最前面做。
     */
    fun imageAt(timeMs: Long): ImageBitmap? {
        val current = session ?: return null
        val frame = current.frameAt(timeMs)
        if (frame == null || kotlin.math.abs(frame.timeMs - timeMs) > current.plan.intervalMs) current.prefer(timeMs)
        return frame?.let(::bitmapOf)
    }

    private fun bitmapOf(frame: ThumbnailFrame): ImageBitmap {
        val bitmap = bitmaps.remove(frame) ?: frame.toImageBitmap()
        bitmaps[frame] = bitmap
        if (bitmaps.size > BITMAP_CACHE) bitmaps.remove(bitmaps.keys.first())
        return bitmap
    }

    /** 拖动中指针到了 [targetMs]。按设置决定主画面要不要跟过去。 */
    fun onDrag(targetMs: Long, durationMs: Long) {
        if (dragPolicy.shouldSeek(dragSeekMode(), targetMs, durationMs, monotonicMillis())) seek(targetMs)
    }

    /** 松手。最后那一次跳转由进度条自己发，这里只把拖动的记录清掉。 */
    fun onDragEnd() = dragPolicy.reset()

    /** 换了视频：旧的位图不再有用。 */
    fun reset() {
        bitmaps.clear()
        session = null
        progress = null
    }

    private companion object {
        const val BITMAP_CACHE = 48
    }
}

val LocalTimelinePreview = compositionLocalOf<TimelinePreview?> { null }

/**
 * 悬停气泡：上面是预览图，下面是时间。图还没生成到这里时只有时间，外加一行「预览生成中」，不留一块空白。
 *
 * @param emphasized 拖动中为 true，用主题色；悬停只是看看，用中性色
 */
@Composable
fun TimelinePreviewBubble(
    preview: TimelinePreview?,
    timeMs: Long,
    timeText: String,
    timeStyle: TextStyle,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 读一下修订号：引擎每多一帧它就变，悬停着不动时图也会自己出来
    val revision = preview?.progress?.revision ?: 0
    val image = remember(preview, timeMs / PREVIEW_STEP_MS, revision) { preview?.imageAt(timeMs) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (emphasized) scheme.primary else scheme.inverseSurface,
        contentColor = if (emphasized) scheme.onPrimary else scheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .width(if (image.width >= image.height) PREVIEW_WIDTH else PREVIEW_WIDTH * image.width / image.height)
                        .aspectRatio(image.width.toFloat() / image.height)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                )
            }
            Text(
                text = timeText,
                style = timeStyle,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (image == null) previewHint(preview?.progress)?.let { hint ->
                Text(hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 3.dp))
            }
        }
    }
}

/** 没有图时写在时间下面的一句话。预览关着或还没开始时不写。 */
private fun previewHint(progress: ThumbnailProgress?): String? = when (progress?.state) {
    null -> null
    ThumbnailState.LoadingCache -> "读取预览缓存"
    ThumbnailState.Generating -> "预览生成中 ${(progress.fraction * 100).toInt()}%"
    ThumbnailState.Yielding -> "预览暂停，播放优先"
    ThumbnailState.Unavailable -> "此视频无法生成预览"
    ThumbnailState.Complete -> null
}

private val PREVIEW_WIDTH = 220.dp

// 指针挪动不到这么多时不重新取图：预览最密也是几秒一张
private const val PREVIEW_STEP_MS = 500L
