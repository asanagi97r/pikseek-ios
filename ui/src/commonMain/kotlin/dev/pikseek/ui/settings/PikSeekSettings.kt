package dev.pikseek.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.settings.SettingsNavigationRow
import dev.piko.ui.screens.settings.SettingsSwitchRow
import dev.pikseek.performance.PerformanceMetrics
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.DragSeekMode
import dev.pikseek.platform.ThumbnailDensity
import dev.pikseek.ui.LocalPikSeek
import kotlinx.coroutines.launch

/**
 * 设置里「播放预览」一节的各行：时间轴预览、缓存、拖动方式、预取与性能浮层。
 * 这些都是每台设备各自的设置，存在数据目录的 pikseek.properties 里，不参与网盘同步。
 */
@Composable
fun PreviewSettingsRows(snackbarHostState: SnackbarHostState) {
    val environment = LocalPikSeek.current
    val settings = environment.settings
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val enabled by settings.thumbnailsEnabled.collectAsState()
    val density by settings.thumbnailDensity.collectAsState()
    val cacheLimit by settings.thumbnailCacheLimit.collectAsState()
    val dragSeek by settings.dragSeekMode.collectAsState()
    val prefetchNext by settings.prefetchNext.collectAsState()
    val overlay by settings.performanceOverlay.collectAsState()
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(Unit) { cacheBytes = environment.previewCache.totalBytes() }

    val count = 8
    SettingsSwitchRow(
        index = 0, count = count,
        icon = Icons.Outlined.PhotoLibrary,
        title = "时间轴预览",
        supporting = "视频开始播放后在后台生成整条进度条的缩略图，悬停与拖动时即时显示",
        checked = enabled,
        onCheckedChange = settings::setThumbnailsEnabled,
    )
    SettingsNavigationRow(
        index = 1, count = count,
        icon = Icons.Outlined.GridView,
        title = "预览密度",
        supporting = "${density.label}：${densityHint(density)}",
        onClick = { dialog = Dialog.Density },
        trailingIcon = null,
    )
    SettingsNavigationRow(
        index = 2, count = count,
        icon = Icons.Outlined.DataUsage,
        title = "预览缓存上限",
        supporting = "${limitLabel(cacheLimit)}，" + if (cacheBytes < 0) "正在统计已用空间" else "已用 ${megabytes(cacheBytes)}。超出时先删最久没看的",
        onClick = { dialog = Dialog.CacheLimit },
        trailingIcon = null,
    )
    SettingsNavigationRow(
        index = 3, count = count,
        icon = Icons.Outlined.CleaningServices,
        title = "清除预览缓存",
        supporting = "删除本机保存的全部缩略图，再次打开视频时重新生成",
        onClick = {
            scope.launch {
                val freed = environment.previewCache.clear()
                cacheBytes = environment.previewCache.totalBytes()
                snackbarHostState.showSnackbar("已清除预览缓存，释放 ${megabytes(freed)}", withDismissAction = true)
            }
        },
        trailingIcon = null,
    )
    SettingsNavigationRow(
        index = 4, count = count,
        icon = Icons.Outlined.PanTool,
        title = "拖动进度条时跳转",
        supporting = "${dragSeek.label}：${dragSeek.description}",
        onClick = { dialog = Dialog.DragSeek },
        trailingIcon = null,
    )
    SettingsSwitchRow(
        index = 5, count = count,
        icon = Icons.Outlined.FastForward,
        title = "预先准备下一条",
        supporting = "播放时提前查好下一个视频的信息（不下载视频），切换时更快",
        checked = prefetchNext,
        onCheckedChange = settings::setPrefetchNext,
    )
    SettingsSwitchRow(
        index = 6, count = count,
        icon = Icons.Outlined.Speed,
        title = "性能浮层",
        supporting = "在播放窗口左上角显示起播、拖动、换集的耗时与预览进度",
        checked = overlay,
        onCheckedChange = settings::setPerformanceOverlay,
    )
    SettingsNavigationRow(
        index = 7, count = count,
        icon = Icons.Outlined.Insights,
        title = "导出性能样本",
        supporting = "本次运行记下的各项耗时，存成文本，可与 Piko 在同一批视频上的数字对比。只在本机",
        onClick = {
            scope.launch {
                val saved = platform.exportLog("pikseek-benchmark-${fileStamp()}.md", PerformanceMetrics.exportText())
                if (saved) snackbarHostState.showSnackbar("已导出性能样本", withDismissAction = true)
            }
        },
        trailingIcon = Icons.Outlined.Download,
    )

    when (dialog) {
        Dialog.Density -> ChoiceDialog(
            title = "预览密度",
            options = ThumbnailDensity.entries.map { Choice(it, it.label, densityHint(it)) },
            selected = density,
            onSelect = settings::setThumbnailDensity,
            onDismiss = { dialog = null },
        )
        Dialog.CacheLimit -> ChoiceDialog(
            title = "预览缓存上限",
            options = AppSettings.CACHE_LIMIT_CHOICES.map { Choice(it, limitLabel(it), null) },
            selected = cacheLimit,
            onSelect = { limit ->
                settings.setThumbnailCacheLimit(limit)
                scope.launch {
                    environment.previewCache.trim(limit)
                    cacheBytes = environment.previewCache.totalBytes()
                }
            },
            onDismiss = { dialog = null },
        )
        Dialog.DragSeek -> ChoiceDialog(
            title = "拖动进度条时跳转",
            options = DragSeekMode.entries.map { Choice(it, it.label, it.description) },
            selected = dragSeek,
            onSelect = settings::setDragSeekMode,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class Dialog { Density, CacheLimit, DragSeek }

private fun densityHint(density: ThumbnailDensity): String = when (density) {
    ThumbnailDensity.Low -> "两小时的视频约 60 张，生成最快"
    ThumbnailDensity.Medium -> "两小时的视频约 120 张"
    ThumbnailDensity.High -> "两小时的视频约 240 张，占用流量与空间加倍"
}

private fun limitLabel(bytes: Long): String = if (bytes <= 0) "不限" else "${bytes / AppSettings.GIB} GB"

internal fun megabytes(bytes: Long): String =
    if (bytes >= AppSettings.GIB) "%.2f GB".format(bytes.toDouble() / AppSettings.GIB) else "%.1f MB".format(bytes / 1024.0 / 1024.0)

internal fun fileStamp(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

internal class Choice<T>(val value: T, val label: String, val description: String?)

/** 几选一的对话框：选中即生效并关闭。 */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.value == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option.value)
                                    onDismiss()
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.value == selected, onClick = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            option.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
