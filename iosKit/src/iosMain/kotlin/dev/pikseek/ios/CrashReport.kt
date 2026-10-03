package dev.pikseek.ios

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.piko.shared.log.PikoLog
import dev.pikseek.platform.TimeText
import dev.pikseek.platform.currentTimeMillis
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import kotlin.time.Instant

/**
 * 闪退记录。Kotlin 一侧没接住的异常在程序退出前写成一个文本文件，放在 Documents/闪退记录 下：
 * 「文件」App →「我的 iPhone」→ PikSeek 里看得到，下次启动也会提示，可以直接分享出去。
 *
 * 里面有异常、调用栈和最近的日志。
 * 日志本来就脱敏过（不含令牌、密码、邮箱、手机号）。
 */
internal object CrashReport {
    private const val FOLDER = FOLDER_NAME
    private const val SEEN_KEY = "pikseek.crash.seen"

    val directory: String by lazy {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: error("找不到 Documents 目录")
        "$documents/$FOLDER"
    }

    private var version = "?"

    @OptIn(ExperimentalNativeApi::class)
    fun install(appVersion: String) {
        version = appVersion
        setUnhandledExceptionHook { error -> runCatching { write("Kotlin 未接住的异常", error.stackTraceToString()) } }
    }

    /** Swift 一侧接住的 Objective-C 异常也写到同一处。 */
    fun writeNative(kind: String, details: String) {
        runCatching { write(kind, details) }
    }

    /** 写一份记录，返回文件路径。 */
    private fun write(kind: String, details: String): String {
        val now = currentTimeMillis()
        val device = UIDevice.currentDevice
        // 日志由后台线程按批写盘，崩溃这一刻最后几行可能还在内存里；最多等一秒
        val log = runCatching { runBlocking { withTimeoutOrNull(1_000) { PikoLog.export() } } }.getOrNull().orEmpty()
        val text = buildString {
            appendLine("PikSeek $version 闪退记录")
            appendLine("时间：${TimeText.stamp(now)}")
            appendLine("系统：${device.systemName} ${device.systemVersion}，${device.model}")
            appendLine("类型：$kind")
            appendLine()
            appendLine(details)
            appendLine()
            appendLine("—— 最近的日志 ——")
            append(log.takeLast(60_000))
        }
        val path = "$directory/PikSeek-${fileStamp(now)}.txt"
        IosFiles.writeText(path, text)
        return path
    }

    /** 上次提示之后新出现的记录（最新的在前）。 */
    fun unseen(): List<String> {
        val seen = platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey(SEEN_KEY).orEmpty()
        val folder = Path(directory)
        if (SystemFileSystem.metadataOrNull(folder)?.isDirectory != true) return emptyList()
        return SystemFileSystem.list(folder).map { it.toString() }
            .filter { it.endsWith(".txt") && Path(it).name > seen }
            .sortedDescending()
    }

    fun markSeen(paths: List<String>) {
        val newest = paths.maxOfOrNull { Path(it).name } ?: return
        platform.Foundation.NSUserDefaults.standardUserDefaults.setObject(newest, forKey = SEEN_KEY)
    }

    /** `20261002-083005`，文件名按它排序就是时间顺序 */
    private fun fileStamp(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        fun Int.p() = toString().padStart(2, '0')
        return "${t.year}${(t.month.ordinal + 1).p()}${t.day.p()}-${t.hour.p()}${t.minute.p()}${t.second.p()}"
    }
}

/** 上次闪退过就提示一次，可以当场把记录分享出去。 */
@Composable
internal fun CrashNotice(native: NativeServices) {
    var reports by remember { mutableStateOf(runCatching { CrashReport.unseen() }.getOrDefault(emptyList())) }
    if (reports.isEmpty()) return
    fun dismiss() {
        CrashReport.markSeen(reports)
        reports = emptyList()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("上次闪退了") },
        text = {
            Text(
                "出错的位置记了下来（${reports.size} 份，不含账号密码）。" +
                    "在「文件」App →「我的 iPhone」→ PikSeek → $FOLDER_NAME 里也能找到。",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newest = reports.first()
                dismiss()
                topViewController()?.let { native.share(newest, it) }
            }) { Text("分享最新一份") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("知道了") } },
    )
}

private const val FOLDER_NAME = "闪退记录"
