package dev.pikseek.security

import dev.pikseek.platform.AppPaths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 安全页与 security-report.txt 的内容。认证模块把自己的状态作为一节交进来（[Section]），
 * 这里补上数据目录、明文凭据扫描、遥测检查与网络审计，拼成纯文本。
 *
 * 写出之前整份过一遍 [SecretRedactor]：各节本来就不含令牌与密码，这一步是防着以后有人往里加了不该加的。
 */
object SecurityReport {
    /** 一节：标题加若干「名称：值」。 */
    class Section(val title: String, val lines: List<Pair<String, String>>)

    class Facts(
        val appVersion: String,
        val dataRoot: String,
        val portable: Boolean,
        /** 数据目录是怎么定下来的，给人看的一句话。 */
        val dataLocation: String,
        val credentialScan: PlaintextCredentialScan.Result,
        val telemetryLibraries: List<String>,
        val unknownHosts: List<String>,
    )

    fun facts(appVersion: String): Facts = Facts(
        appVersion = appVersion,
        // 先取根目录：它是怎么定下来的（便携、指定、用户目录）在取的时候才确定
        dataRoot = AppPaths.dataRoot.toString(),
        portable = AppPaths.isPortable,
        dataLocation = AppPaths.location.label,
        credentialScan = PlaintextCredentialScan.scan(AppPaths.dataRoot),
        telemetryLibraries = TelemetryCheck.present(),
        unknownHosts = NetworkAudit.unknownHosts(),
    )

    fun build(
        facts: Facts,
        sections: List<Section>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(zone)
        val text = buildString {
            appendLine("PikSeek Security Report")
            appendLine("generated: ${time.format(Instant.ofEpochMilli(nowMillis))}")
            appendLine("version:   ${facts.appVersion}")
            appendLine()
            sections.forEach { section ->
                appendLine("== ${section.title} ==")
                section.lines.forEach { (name, value) -> appendLine("$name: $value") }
                appendLine()
            }
            appendLine("== Local storage ==")
            appendLine("data directory: ${facts.dataRoot}")
            appendLine("data location: ${facts.dataLocation}")
            appendLine("portable (data beside the program): ${yesNo(facts.portable)}")
            appendLine("files scanned for plaintext credentials: ${facts.credentialScan.scannedFiles}")
            appendLine("plaintext credential files: ${facts.credentialScan.count}")
            facts.credentialScan.suspicious.forEach { appendLine("    suspicious: $it") }
            appendLine()
            appendLine("== Telemetry ==")
            appendLine("telemetry: Disabled (no telemetry, analytics, crash upload or ad code in this program)")
            appendLine(
                "telemetry libraries on classpath: " +
                    if (facts.telemetryLibraries.isEmpty()) "none" else facts.telemetryLibraries.joinToString(),
            )
            appendLine("update check: removed (the program never contacts a release server)")
            appendLine("crash logs: local only, under the data directory")
            appendLine()
            appendLine("== Network ==")
            appendLine("non-PikPak hosts contacted this run: ${facts.unknownHosts.size}")
            facts.unknownHosts.forEach { appendLine("    $it") }
            appendLine()
            append(NetworkAudit.exportText(nowMillis, zone))
        }
        return SecretRedactor.redact(text)
    }

    private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
}
