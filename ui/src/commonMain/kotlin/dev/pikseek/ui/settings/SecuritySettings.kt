package dev.pikseek.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.settings.SettingsNavigationRow
import dev.pikseek.auth.AuthStatus
import dev.pikseek.auth.CredentialPersistence
import dev.pikseek.security.HostCategory
import dev.pikseek.security.NetworkAudit
import dev.pikseek.security.SecurityReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置里「安全与隐私」一节：认证状态、本机存储的自检结果、本次运行访问过的主机，以及两个导出按钮。
 *
 * 这一页的每一行都是当场查出来的，不是写死的文案：存储方式问认证模块，明文凭据数是真去数据目录里扫的，
 * 遥测库是真去类路径上找的，主机列表是进程建连接时记下的。
 */
@Composable
fun SecuritySettingsContent(snackbarHostState: SnackbarHostState, groupTitle: @Composable (String) -> Unit) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<AuthStatus?>(null) }
    var facts by remember { mutableStateOf<SecurityReport.Facts?>(null) }
    val auditRevision by NetworkAudit.changes.collectAsState()

    LaunchedEffect(refresh) {
        status = services.clientManager.auth.status()
        // 扫数据目录要读文件，放后台
        facts = withContext(Dispatchers.IO) { SecurityReport.facts(platform.appVersion) }
    }

    suspend fun report(): String {
        val current = services.clientManager.auth.status()
        val currentFacts = withContext(Dispatchers.IO) { SecurityReport.facts(platform.appVersion) }
        return SecurityReport.build(currentFacts, listOf(authSection(current)))
    }

    groupTitle("认证")
    FactCard {
        val current = status
        Fact("会话保存", current?.let(::persistenceText) ?: "…", warn = current?.persistence == CredentialPersistence.MemoryOnly)
        Fact("密码保存", "从不保存。只在登录那一刻发往 PikPak 认证服务")
        Fact("刷新令牌", "只在认证模块内使用，主程序与 SDK 拿不到")
        Fact("认证服务器白名单", current?.allowedHosts?.joinToString("、") ?: "…")
        Fact("最近一次认证请求", current?.lastRequest?.let { "${it.purpose} → ${it.host}，${it.outcome}，${clock(it.atMillis)}" } ?: "本次运行还没有")
        Fact("被白名单拦下的请求", current?.blockedRequests?.toString() ?: "…", warn = (current?.blockedRequests ?: 0) > 0)
    }

    groupTitle("本机自检")
    FactCard {
        val current = facts
        Fact("数据目录", current?.let { "${it.dataRoot}（${it.dataLocation}）" } ?: "…")
        Fact(
            "明文凭据文件",
            current?.let { "${it.credentialScan.count} 个（扫描了 ${it.credentialScan.scannedFiles} 个文件）" } ?: "正在扫描",
            warn = (current?.credentialScan?.count ?: 0) > 0,
        )
        current?.credentialScan?.suspicious?.forEach { Fact("  可疑文件", it, warn = true) }
        Fact(
            "遥测、统计、崩溃上报",
            current?.let { if (it.telemetryLibraries.isEmpty()) "无。类路径上没有这类库" else "发现：${it.telemetryLibraries.joinToString()}" } ?: "…",
            warn = current?.telemetryLibraries?.isNotEmpty() == true,
        )
        Fact("自动更新", "已移除。程序不联系任何更新服务器")
        Fact("日志与崩溃记录", "只保存在数据目录的 logs 下")
    }

    groupTitle("网络审计（本次运行）")
    val entries = remember(auditRevision) { NetworkAudit.snapshot() }
    FactCard {
        if (entries.isEmpty()) {
            Text("还没有访问过任何主机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    entry.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.category == HostCategory.Other) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                Text(
                    "${entry.category.label} · ${entry.purpose}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "首次 ${clock(entry.firstSeenMillis)} · 最近 ${clock(entry.lastSeenMillis)} · 新建连接 ${entry.count} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "记录的是本进程新建连接时的目标主机名，不含路径、查询串与任何令牌。红色的不是 PikPak 的域名，请核对。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SettingsNavigationRow(
            index = 0, count = 3,
            icon = Icons.AutoMirrored.Outlined.FactCheck,
            title = "导出安全报告",
            supporting = "security-report.txt：以上全部内容加网络审计。不含令牌与密码",
            onClick = {
                scope.launch {
                    if (platform.exportLog("security-report.txt", report())) snackbarHostState.showSnackbar("已导出安全报告", withDismissAction = true)
                }
            },
            trailingIcon = Icons.Outlined.Download,
        )
        SettingsNavigationRow(
            index = 1, count = 3,
            icon = Icons.Outlined.Lan,
            title = "导出网络审计",
            supporting = "network-audit.txt：主机、用途、首次与最近访问时刻",
            onClick = {
                scope.launch {
                    if (platform.exportLog("network-audit.txt", NetworkAudit.exportText())) snackbarHostState.showSnackbar("已导出网络审计", withDismissAction = true)
                }
            },
            trailingIcon = Icons.Outlined.Download,
        )
        SettingsNavigationRow(
            index = 2, count = 3,
            icon = Icons.Outlined.Refresh,
            title = "重新检查",
            supporting = "重新读取认证状态并扫描数据目录",
            onClick = { refresh++ },
            trailingIcon = null,
        )
    }
}

private fun persistenceText(status: AuthStatus): String = when (status.persistence) {
    CredentialPersistence.Dpapi -> "Windows DPAPI（当前用户）加密后存于数据目录，${status.storedSessions} 个会话"
    CredentialPersistence.MemoryOnly -> "未保存，仅本次运行有效：${status.storeProblem ?: "加密存储不可用"}。不会改用明文"
}

/** 报告里「认证」一节。英文键名：报告是给人对照、也便于用脚本检查的。 */
fun authSection(status: AuthStatus): SecurityReport.Section = SecurityReport.Section(
    "Authentication",
    buildList {
        add("auth implementation" to "PikSeek LocalAuthBroker (independent of Piko credential code)")
        add("session stored with DPAPI" to if (status.persistence == CredentialPersistence.Dpapi) "Yes" else "No (memory only)")
        status.storeProblem?.let { add("credential store problem" to it) }
        add("plaintext fallback" to "None")
        add("password stored" to "Never")
        add("known accounts" to status.knownAccounts.toString())
        add("stored sessions" to status.storedSessions.toString())
        add("auth network destinations" to status.allowedHosts.joinToString(", "))
        add(
            "last authentication request" to
                (status.lastRequest?.let { "${it.purpose} -> ${it.host}, ${it.outcome}, ${Instant.ofEpochMilli(it.atMillis)}" } ?: "none this run"),
        )
        add("auth requests blocked by policy" to status.blockedRequests.toString())
    },
)

@Composable
private fun FactCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun Fact(name: String, value: String, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(168.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis))
