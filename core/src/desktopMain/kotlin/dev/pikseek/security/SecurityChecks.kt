package dev.pikseek.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * 在数据目录里找明文凭据：真去读文件，而不是写死一个 0。
 *
 * 认的是凭据落盘时的样子：JSON 或 key=value 里的 access_token、refresh_token、password，以及 JWT。
 * DPAPI 的密文是二进制，对不上这些样子；日志若真漏进了令牌，这里会把那个文件数出来。
 */
object PlaintextCredentialScan {
    class Result(val scannedFiles: Int, val suspicious: List<String>) {
        val count: Int get() = suspicious.size
    }

    private val PATTERNS = listOf(
        Regex("""(?i)"(access_token|refresh_token|password|passwd)"\s*:\s*"[^"]{6,}""""),
        Regex("""(?i)\b(access_token|refresh_token|password|passwd)\s*=\s*[^\s&]{6,}"""),
        Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}"""),
    )

    // 图片、视频切片与块缓存不会是文本凭据，文件又大又多，跳过
    private val SKIPPED_EXTENSIONS = setOf("webp", "jpg", "jpeg", "png", "ts", "mp4", "mkv", "blk", "bin", "dll", "jar", "aot")
    private const val MAX_BYTES = 4L * 1024 * 1024

    fun scan(root: Path): Result {
        if (!Files.isDirectory(root)) return Result(0, emptyList())
        var scanned = 0
        val suspicious = ArrayList<String>()
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                if (file.extension.lowercase() in SKIPPED_EXTENSIONS) return@forEach
                val size = runCatching { file.fileSize() }.getOrDefault(0L)
                if (size == 0L || size > MAX_BYTES) return@forEach
                // 按单字节读：不管原本是什么编码，ASCII 的键名与令牌都原样可见，二进制文件也不会抛
                val text = runCatching { String(Files.readAllBytes(file), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
                scanned++
                if (PATTERNS.any { it.containsMatchIn(text) }) suspicious += root.relativize(file).toString()
            }
        }
        return Result(scanned, suspicious)
    }
}

/**
 * 类路径上有没有常见的遥测、统计、崩溃上报库。PikSeek 一个都不依赖；这里按类名实际查一遍，
 * 往后有人加依赖把它们带进来，安全页当场就会显示出来。
 */
object TelemetryCheck {
    private val KNOWN = mapOf(
        "io.sentry.Sentry" to "Sentry",
        "com.google.firebase.analytics.FirebaseAnalytics" to "Firebase Analytics",
        "com.google.firebase.crashlytics.FirebaseCrashlytics" to "Firebase Crashlytics",
        "com.google.android.gms.analytics.GoogleAnalytics" to "Google Analytics",
        "com.amplitude.Amplitude" to "Amplitude",
        "com.amplitude.api.Amplitude" to "Amplitude",
        "com.mixpanel.mixpanelapi.MixpanelAPI" to "Mixpanel",
        "com.bugsnag.Bugsnag" to "Bugsnag",
        "com.microsoft.appcenter.AppCenter" to "App Center",
        "com.datadog.android.Datadog" to "Datadog",
        "com.posthog.PostHog" to "PostHog",
        "com.segment.analytics.Analytics" to "Segment",
        "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter" to "OpenTelemetry exporter",
        "com.newrelic.api.agent.NewRelic" to "New Relic",
        "com.google.android.gms.ads.MobileAds" to "Google Ads",
    )

    /** 找到的库名，去重。空表示一个都没有。 */
    fun present(loader: ClassLoader = TelemetryCheck::class.java.classLoader): List<String> =
        KNOWN.filter { (className, _) -> runCatching { Class.forName(className, false, loader) }.isSuccess }
            .values.distinct()
}
