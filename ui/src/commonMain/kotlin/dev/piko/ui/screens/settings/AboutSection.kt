package dev.piko.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.piko.shared.log.PikoLog
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * 关于：版本、出处与日志。有「我的」页时放在那里，设置入口的下面；有整条侧边栏的宽窗口里收进设置。
 *
 * 没有更新这一项：PikSeek 不联系任何更新服务器，换新版是换一个文件夹。
 * [snackbarHostState] 取宿主页面的，清除日志的提示显示在那里。
 */
@Composable
internal fun AboutSection(snackbarHostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        AboutCard(
            version = platform.appVersion,
            onOpenUpstream = { platform.openUrl(UPSTREAM_URL) },
        )
        SettingsNavigationRow(
            index = 0,
            count = 2,
            icon = Icons.Outlined.BugReport,
            title = "导出日志",
            supporting = "排查问题用。只记录操作经过，不含文件名、账号、密码与令牌；只保存在本机",
            onClick = { scope.launch { exportLogs(platform) } },
            trailingIcon = null,
        )
        // 日志只留两天，这里给的是复现之前手动清一次：导出的就只有这一次的经过
        SettingsNavigationRow(
            index = 1,
            count = 2,
            icon = Icons.Outlined.History,
            title = "清除日志",
            supporting = "自动保留最近两天。复现问题之前清除一次，导出的内容更清楚",
            onClick = {
                scope.launch {
                    PikoLog.clear()
                    snackbarHostState.showSnackbar("已清除日志", withDismissAction = true)
                }
            },
            trailingIcon = null,
        )
    }
}

// PikSeek 的文件管理、取流与播放器取自这个项目（MIT 许可）
private const val UPSTREAM_URL = "https://github.com/NihilDigit/piko"

/** 开头写明版本与运行环境；文件名带时间，多次导出不会互相覆盖。 */
private suspend fun exportLogs(platform: PikoPlatform) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun Int.pad() = toString().padStart(2, '0')
    val stamp = "${now.year}${now.month.number.pad()}${now.day.pad()}-${now.hour.pad()}${now.minute.pad()}${now.second.pad()}"
    val header = "PikSeek ${platform.appVersion}\n${platform.deviceSummary}\n导出于 $now\n\n"
    platform.exportLog("pikseek-log-$stamp.txt", header + PikoLog.export())
}

@Composable
private fun AboutCard(version: String, onOpenUpstream: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PikoBrandIcons.Glyph, contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("PikSeek", style = MaterialTheme.typography.titleMedium)
                    Text("版本 $version", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "第三方 PikPak 桌面客户端，与 PikPak 官方无关。文件管理、取流与播放器基于开源项目 Piko（MIT 许可，© NihilDigit）；" +
                    "登录认证是独立实现的，另加了时间轴缩略图预览、网络审计与安全报告。不含遥测与自动更新。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenUpstream) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("原项目 Piko")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
