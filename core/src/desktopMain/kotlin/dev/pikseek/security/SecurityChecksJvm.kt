package dev.pikseek.security

import dev.pikseek.platform.AppPaths
import java.io.File
import java.nio.file.Path

/** 结果里的路径按本机的分隔符写。 */
fun PlaintextCredentialScan.scan(root: Path): PlaintextCredentialScan.Result {
    val result = scan(root.toString())
    return PlaintextCredentialScan.Result(result.scannedFiles, result.suspicious.map { it.replace('/', File.separatorChar) })
}

/**
 * 类路径上有没有常见的遥测、统计、崩溃上报库。按类名实际查一遍。
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

actual fun telemetryLibrariesPresent(): List<String> = TelemetryCheck.present()

actual fun collectSecurityFacts(appVersion: String): SecurityReport.Facts = SecurityReport.Facts(
    appVersion = appVersion,
    // 先取根目录：它是怎么定下来的（便携、指定、用户目录）在取的时候才确定
    dataRoot = AppPaths.dataRoot.toString(),
    portable = AppPaths.isPortable,
    dataLocation = AppPaths.location.label,
    credentialScan = PlaintextCredentialScan.scan(AppPaths.dataRoot),
    telemetryLibraries = telemetryLibrariesPresent(),
    unknownHosts = NetworkAudit.unknownHosts(),
)
