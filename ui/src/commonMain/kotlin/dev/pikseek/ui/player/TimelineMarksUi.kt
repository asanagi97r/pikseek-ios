package dev.pikseek.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pikseek.thumbnail.MediaMarks

/** 一处改分段的操作：菜单与面板里各列一遍。 */
class MarksAction(val label: String, val apply: () -> Unit)

/**
 * 在 [timeMs] 处能对分段做的事。[toleranceMs] 是「附近」有多近：离得这么近的分点才删得掉。
 * 还没有分段（没读到，或这个视频认不出）时为空。
 */
fun marksActions(preview: TimelinePreview?, at: Long, toleranceMs: Long): List<MarksAction> {
    val marks = preview?.marks ?: return emptyList()
    // 取整到秒：菜单上写的是几分几秒，加进去的就是那一秒
    val timeMs = at / 1000 * 1000
    val clock = formatClock(timeMs)
    return buildList {
        val near = marks.scenes.minByOrNull { kotlin.math.abs(it - timeMs) }?.takeIf { kotlin.math.abs(it - timeMs) <= toleranceMs }
        if (near != null) {
            add(MarksAction("删掉 ${formatClock(near)} 的分点") { preview.editMarks { it.withoutScene(near, 1) } })
        } else if (marks.withScene(timeMs) != marks) {
            add(MarksAction("在 $clock 加分点") { preview.editMarks { it.withScene(timeMs) } })
        }
        if (marks.withIntroEnd(timeMs) != marks) add(MarksAction("片头到 $clock 结束") { preview.editMarks { it.withIntroEnd(timeMs) } })
        if (marks.withOutroStart(timeMs) != marks) add(MarksAction("片尾从 $clock 开始") { preview.editMarks { it.withOutroStart(timeMs) } })
        if (marks.intro != null || marks.outro != null) add(MarksAction("清除片头片尾") { preview.editMarks { it.withoutIntroOutro() } })
    }
}

/** 进度条右键菜单的各项。 */
@Composable
fun MarksMenuItems(actions: List<MarksAction>, onDone: () -> Unit) {
    actions.forEach { action ->
        DropdownMenuItem(text = { Text(action.label) }, onClick = {
            action.apply()
            onDone()
        })
    }
}

/**
 * 播放设置面板里的「进度条分段」一节：触屏没有右键，改分段从这里改，按的是眼下播放到的位置。
 * 还没有分段时不出现。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineMarksSection(preview: TimelinePreview?, positionMillis: Long) {
    val marks = preview?.marks ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("进度条分段", style = MaterialTheme.typography.titleSmall)
        Text(describe(marks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            marksActions(preview, positionMillis, NEAR_POSITION_MS).forEach { action ->
                OutlinedButton(onClick = action.apply) { Text(action.label) }
            }
        }
    }
}

private fun describe(marks: MediaMarks): String = buildString {
    append(if (marks.segmentCount > 1) "${marks.segmentCount} 段" else if (marks.scenesDone) "没有找到场景分点" else "场景分点还没做")
    marks.intro?.let { append(" · 片头 ${formatClock(it.startMs)}–${formatClock(it.endMs)}") }
    marks.outro?.let { append(" · 片尾 ${formatClock(it.startMs)}–${formatClock(it.endMs)}") }
    if (marks.scenesEdited || marks.episodeEdited) append(" · 手动改过")
}

/**
 * 正在片头或片尾里时浮出的「跳过片头」「跳过片尾」。片尾有下一集时跳到下一集。
 * 设置里开了自动跳过时，进到片头片尾就直接跳，每一段只自动跳一次（跳回来重看不再跳）。
 */
@Composable
fun SkipSegmentButton(
    preview: TimelinePreview?,
    positionMillis: Long,
    hasNext: Boolean,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marks = preview?.marks ?: return
    val intro = marks.intro?.takeIf { positionMillis in it.startMs until it.endMs - SKIP_TAIL_MS }
    val outro = marks.outro?.takeIf { positionMillis in it.startMs until it.endMs - SKIP_TAIL_MS }
    val currentHasNext by rememberUpdatedState(hasNext)
    // 自动跳过过的段（按开头时刻记）：同一段只跳一次
    var autoSkipped by remember(marks.gcid) { mutableStateOf(emptySet<Long>()) }
    fun skip(span: MediaMarks.Span, isOutro: Boolean) {
        if (isOutro && currentHasNext) onNext() else onSeek(span.endMs)
    }
    val active = intro ?: outro
    LaunchedEffect(active?.startMs) {
        val span = active ?: return@LaunchedEffect
        if (preview.autoSkip() && span.startMs !in autoSkipped) {
            autoSkipped = autoSkipped + span.startMs
            skip(span, isOutro = span == outro)
        }
    }
    if (active == null) return
    val isOutro = intro == null
    FilledTonalButton(onClick = { skip(active, isOutro) }, modifier = modifier) {
        Text(if (isOutro) (if (hasNext) "跳过片尾 · 下一集" else "跳过片尾") else "跳过片头")
        Icon(if (isOutro && hasNext) Icons.Filled.SkipNext else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

/** 时刻写成「1:02:03」或「2:03」。 */
internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}" else "$minutes:${seconds.toString().padStart(2, '0')}"
}

// 面板里按眼下的播放位置改：离它这么近的分点算「附近」
private const val NEAR_POSITION_MS = 15_000L

// 离段尾不到这么多时不再出跳过按钮：马上就过去了
private const val SKIP_TAIL_MS = 2_000L
