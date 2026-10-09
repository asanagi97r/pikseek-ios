package dev.pikseek.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.ui.screens.player.playerContainerColor
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.PlayOrder
import kotlinx.coroutines.launch

/**
 * 播放器里改连续播放与播放顺序要用的设置。播放窗口提供；没提供时（测试、别的窗口）不画按钮也不画那一节。
 */
val LocalPlaybackSettings = staticCompositionLocalOf<AppSettings?> { null }

/** 底栏按钮一下一下切过去的次序：顺序 → 随机 → 列表循环 → 单集循环 → 播完停止 → 顺序。null 是「播完停止」。 */
private val CYCLE: List<PlayOrder?> = listOf(PlayOrder.Sequential, PlayOrder.Shuffle, PlayOrder.LoopList, PlayOrder.RepeatOne, null)

private fun PlayOrder?.icon(): ImageVector = when (this) {
    PlayOrder.Sequential -> Icons.AutoMirrored.Filled.PlaylistPlay
    PlayOrder.Shuffle -> Icons.Filled.Shuffle
    PlayOrder.LoopList -> Icons.Filled.Repeat
    PlayOrder.RepeatOne -> Icons.Filled.RepeatOne
    null -> Icons.Outlined.StopCircle
}

private fun PlayOrder?.label(): String = this?.label ?: "播完停止"

/**
 * 底栏上的连播按钮：图标是眼下的方式，点一下换下一种，换完把名字浮出来看一眼。
 * 与播放设置里的那一节改的是同一份设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOrderButton(modifier: Modifier = Modifier) {
    val settings = LocalPlaybackSettings.current ?: return
    val continuous by settings.continuousPlay.collectAsState()
    val order by settings.playOrder.collectAsState()
    val current: PlayOrder? = if (continuous) order else null
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(current.label()) } },
        state = tooltip,
        modifier = modifier,
    ) {
        FilledTonalIconButton(
            onClick = {
                val next = CYCLE[(CYCLE.indexOf(current) + 1) % CYCLE.size]
                if (next == null) {
                    settings.setContinuousPlay(false)
                } else {
                    settings.setContinuousPlay(true)
                    settings.setPlayOrder(next)
                }
                scope.launch { tooltip.show() }
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = playerContainerColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.semantics { contentDescription = "连播：${current.label()}，点按切换" },
        ) {
            Icon(current.icon(), contentDescription = null)
        }
    }
}

/** 播放设置面板里的一节：连续播放的开关，开着时再选顺序。 */
@Composable
fun PlaybackOrderSection() {
    val settings = LocalPlaybackSettings.current ?: return
    val continuous by settings.continuousPlay.collectAsState()
    val order by settings.playOrder.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("连续播放", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (continuous) "放完自动接着放同一个文件夹里的下一个" else "放完停在片尾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = continuous, onCheckedChange = settings::setContinuousPlay)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PlayOrder.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = order == option,
                    onClick = { settings.setPlayOrder(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, PlayOrder.entries.size),
                    enabled = continuous,
                    icon = {},
                    label = { Text(option.label.removeSuffix("播放"), fontSize = 13.sp, maxLines = 1) },
                )
            }
        }
    }
}
