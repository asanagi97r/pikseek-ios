package dev.pikseek.security

import dev.pikseek.platform.IosPaths
import platform.Foundation.NSClassFromString

// 常见的遥测、统计、崩溃上报与广告库在 iOS 上的入口类。链接进了程序，这些类名就查得到
private val KNOWN = mapOf(
    "SentrySDK" to "Sentry",
    "FIRAnalytics" to "Firebase Analytics",
    "FIRCrashlytics" to "Firebase Crashlytics",
    "GAI" to "Google Analytics",
    "Amplitude" to "Amplitude",
    "Mixpanel" to "Mixpanel",
    "Bugsnag" to "Bugsnag",
    "MSACAppCenter" to "App Center",
    "DDDatadog" to "Datadog",
    "PHGPostHog" to "PostHog",
    "SEGAnalytics" to "Segment",
    "NewRelic" to "New Relic",
    "GADMobileAds" to "Google Ads",
    "FBSDKAppEvents" to "Facebook SDK",
    "Flurry" to "Flurry",
    "AppsFlyerLib" to "AppsFlyer",
    "Adjust" to "Adjust",
)

actual fun telemetryLibrariesPresent(): List<String> =
    KNOWN.filter { (className, _) -> NSClassFromString(className) != null }.values.distinct()

actual fun collectSecurityFacts(appVersion: String): SecurityReport.Facts = SecurityReport.Facts(
    appVersion = appVersion,
    dataRoot = IosPaths.dataRoot,
    portable = false,
    dataLocation = "程序沙盒内；登录会话在钥匙串",
    credentialScan = scanSandbox(),
    telemetryLibraries = telemetryLibrariesPresent(),
    unknownHosts = NetworkAudit.unknownHosts(),
)

/** 要留着的数据与能重建的缓存都扫。 */
private fun scanSandbox(): PlaintextCredentialScan.Result {
    val data = PlaintextCredentialScan.scan(IosPaths.dataRoot)
    val cache = PlaintextCredentialScan.scan(IosPaths.cacheRoot)
    return PlaintextCredentialScan.Result(data.scannedFiles + cache.scannedFiles, data.suspicious + cache.suspicious.map { "Caches/$it" })
}
