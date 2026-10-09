package dev.pikseek.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.ui.components.TooltipIconButton
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.ui.LocalPreviewPacks
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘页顶上的「预览缓存」按钮。有任务在做时外面绕一圈进度。没有 [LocalPreviewPacks] 时不画。
 */
@Composable
fun PreviewCacheButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val control = LocalPreviewPacks.current ?: return
    val jobs by control.jobs.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        TooltipIconButton(Icons.Outlined.Preview, if (jobs.running) "预览缓存（进行中）" else "预览缓存", onClick)
        if (jobs.running) {
            val fraction = if (jobs.total > 0) (jobs.finished + jobs.currentFraction) / jobs.total else null
            if (fraction == null) {
                CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(36.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * 视频缩略图角上的预览缓存标记：正在做时写「生成 37%」，网盘上有包时写「预览」（做完）或「预览 37%」（没做完）。
 * 文件夹、非视频、没有 gcid、没有包的都不画。
 */
@Composable
fun PreviewCacheBadge(file: FileStat, modifier: Modifier = Modifier) {
    val control = LocalPreviewPacks.current ?: return
    if (file.isFolder || !file.isPlayableVideo()) return
    val gcid = file.hash.uppercase()
    if (gcid.isBlank()) return
    val live by control.live.collectAsState()
    val packs by control.packs.collectAsState()
    val generating = live[gcid]
    val pack = packs[gcid]?.name
    val (text, complete) = when {
        generating != null -> "生成 ${percent(generating)}" to false
        pack == null -> return
        pack.isComplete -> "预览" to true
        else -> "预览 ${percent(pack.fraction)}" to false
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (complete) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF7BD88F), modifier = Modifier.size(10.dp))
        Text(text, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

/**
 * 「预览缓存」弹窗：选范围与档次，开始做；有任务在做时显示进度、可以停。
 *
 * 档次与设置里的「预览密度」分开：那个管看视频时现场生成（没有缓存时）的，这里管存到网盘上的。
 */
@Composable
fun PreviewCacheDialog(target: PreviewCacheTarget, onDismiss: () -> Unit) {
    val control = LocalPreviewPacks.current ?: return
    val jobs by control.jobs.collectAsState()
    val isFolder = target is PreviewCacheTarget.Folder
    var includeSubfolders by rememberSaveable { mutableStateOf(false) }
    var overwrite by rememberSaveable { mutableStateOf(false) }
    var density by remember { mutableStateOf(lastDensity) }
    var scenes by remember { mutableStateOf(lastScenes) }
    var episodes by remember { mutableStateOf(lastEpisodes) }
    // 认片头片尾要几集互相比：只点了一个视频时没得比
    val canCompare = control.canFindEpisodes && (isFolder || (target as PreviewCacheTarget.Files).files.size > 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Preview, contentDescription = null) },
        title = { Text("预览缓存") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (target) {
                        is PreviewCacheTarget.Folder -> "给「${target.name}」里的视频先做好进度条预览，存到网盘上。下次打开、换台设备登录都不用再等。"
                        is PreviewCacheTarget.Files -> {
                            val name = target.files.singleOrNull()?.name
                            if (name != null) "给「$name」做好进度条预览，存到网盘上。已有的原位换掉。" else "给选中的 ${target.files.size} 个视频做好进度条预览，存到网盘上。已有的原位换掉。"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("档次", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PreviewDensity.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = density == option,
                                onClick = { density = option },
                                shape = SegmentedButtonDefaults.itemShape(index, PreviewDensity.entries.size),
                            ) { Text(option.label) }
                        }
                    }
                    Text(densityHint(density), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CheckRow(
                    "同时标场景分点",
                    "找出换场景的地方，在进度条上画刻度、拖动时吸住。每个视频多取几十帧，约多花预览三分之一的时间。",
                    scenes,
                ) { scenes = it }
                if (canCompare) {
                    CheckRow(
                        "认片头片尾（剧集）",
                        "同一个文件夹里的几集互相比声音，认出片头曲、片尾曲，播放时可一键跳过。" +
                            "每集要读开头 5 分钟、结尾 4 分钟（低清转码流约几十 MB）。75 分钟以上的不认。",
                        episodes,
                    ) { episodes = it }
                }
                if (isFolder) {
                    CheckRow("包括子文件夹", null, includeSubfolders) { includeSubfolders = it }
                    CheckRow(
                        "已有的也重做",
                        "不勾时：已做完且档次相同的跳过，没做完的接着做，档次不同的按这次的重做。",
                        overwrite,
                    ) { overwrite = it }
                }
                Text(
                    "存在网盘根目录的「${PreviewCloud.FOLDER_NAME}」文件夹里，每个视频一个几百 KB 的预览、一个很小的分段文件。" +
                        "按视频内容（PikPak 的 GCID）认：视频改名、移动到别的文件夹都还认得。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JobsStatus(jobs, onStop = control::cancel)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                lastDensity = density
                lastScenes = scenes
                if (canCompare) lastEpisodes = episodes
                control.start(
                    PreviewCacheRequest(
                        target = target,
                        density = density,
                        includeSubfolders = isFolder && includeSubfolders,
                        // 点名的视频：再点一次就是要重做
                        overwrite = !isFolder || overwrite,
                        scenes = scenes,
                        episodes = canCompare && episodes,
                    ),
                )
                onDismiss()
            }) { Text(if (jobs.running) "排到后面" else "开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun JobsStatus(jobs: PreviewJobsState, onStop: () -> Unit) {
    if (!jobs.running && jobs.summary == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (jobs.running) {
                Text("正在做：${jobs.label}", style = MaterialTheme.typography.labelLarge, maxLines = 2)
                val counts = if (jobs.scanning) "在找视频，已找到 ${jobs.total} 个" else "${jobs.finished} / ${jobs.total} 个视频"
                Text(counts + if (jobs.queued > 0) "，后面还有 ${jobs.queued} 批" else "", style = MaterialTheme.typography.bodySmall)
                if (jobs.total > 0) {
                    LinearProgressIndicator(progress = { ((jobs.finished + jobs.currentFraction) / jobs.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                jobs.current?.let {
                    Text("$it  ${percent(jobs.currentFraction)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onStop, modifier = Modifier.align(Alignment.End)) { Text("停止") }
            } else {
                Text(jobs.summary.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val PreviewDensity.label: String
    get() = when (this) {
        PreviewDensity.Low -> "低"
        PreviewDensity.Medium -> "中"
        PreviewDensity.High -> "高"
    }

private fun densityHint(density: PreviewDensity): String = when (density) {
    PreviewDensity.Low -> "半小时以内 30 张，1～2 小时 60 张。最快，文件最小。"
    PreviewDensity.Medium -> "半小时以内 60 张，1～2 小时 120 张，2～3 小时 180 张。"
    PreviewDensity.High -> "张数是中档的两倍（短片最密 3 秒一张）。最慢，文件约大一倍。"
}

private fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

/** 上次选的档次与勾选，弹窗再开时沿用。只在这次运行里记着。 */
private var lastDensity: PreviewDensity = PreviewDensity.Medium
private var lastScenes: Boolean = true
private var lastEpisodes: Boolean = false
