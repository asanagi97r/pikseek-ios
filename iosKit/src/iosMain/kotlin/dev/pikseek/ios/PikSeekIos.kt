package dev.pikseek.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.piko.data.auth.StoredPikoPreferences
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.ui.theme.appearanceFlow
import dev.pikseek.auth.AuthBroker
import dev.pikseek.auth.IosAuthPlatform
import dev.pikseek.auth.KeychainCredentialStore
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.IosPaths
import dev.pikseek.platform.UserDefaultsSettingsStore
import dev.pikseek.thumbnail.IosThumbnailPlatform
import dev.pikseek.ui.LocalPikSeek
import dev.pikseek.ui.LocalPreviewPacks
import dev.pikseek.ui.PreviewRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.pikseek.auth.PikPakAuthClient
import dev.pikseek.auth.defaultAuthTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * iOS 版的入口。Swift 一侧在程序启动时调 [start] 把系统能力交进来，再用 [mainViewController] 取界面。
 *
 * 拼出来的东西与桌面版的 Main 相同：认证（钥匙串）、PikPak 客户端、取流与本机代理、下载上传、
 * 时间轴预览，然后是 Piko 的界面。播放页在应用内压一层全屏页。
 */
object PikSeekIos {
    private class Runtime(
        val native: NativeServices,
        val services: PikoServices,
        val platform: IosPikoPlatform,
        val preview: PreviewRuntime,
        val appearance: Flow<Appearance>,
        val initialAppearance: Appearance,
    )

    private var runtime: Runtime? = null

    /** 只调一次，在取界面之前。 */
    fun start(native: NativeServices) = boot(native, uiTest = false)

    /**
     * 打包流程在模拟器里用的「已登录」启动：认证请求由一个假的服务端答复，拿到一个假令牌，然后照常进主界面。
     * 网盘接口拿着假令牌会被 PikPak 拒绝，界面走出错的路子；要看的是登录之后这一段在 iOS 上会不会崩。
     * 只由环境变量 PIKSEEK_UITEST 触发，手机上正常点开走不到这里。
     */
    fun startUiTest(native: NativeServices) = boot(native, uiTest = true)

    /** Swift 一侧接住的 Objective-C 异常，写进闪退记录。 */
    fun recordNativeCrash(kind: String, details: String) = CrashReport.writeNative(kind, details)

    private fun boot(native: NativeServices, uiTest: Boolean) {
        if (runtime != null) return
        installLog(native)
        CrashReport.install(native.appVersion())
        IosPaths.cleanTempDirectory()

        // 认证模块要的两样系统能力，先于 AuthBroker 交进去
        IosAuthPlatform.http = AuthHttpAdapter(native.http())
        IosAuthPlatform.keychain = KeychainAdapter(native.keychain())
        IosThumbnailPlatform.grabberFactory = { width -> native.createFrameGrabber(width)?.let(::FrameGrabberAdapter) }

        val downloads = "${documentsDirectory()}/Downloads"
        val settings = IosKeyValueSettings()
        val preferences = StoredPikoPreferences(settings, defaultDownloadDirectory = { downloads })
        val appSettings = AppSettings(UserDefaultsSettingsStore())

        // 进程级作用域：下载与会话刷新不随某个页面的组合结束
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val authBroker = AuthBroker(
            store = KeychainCredentialStore(KeychainAdapter(native.keychain())),
            transport = if (uiTest) uiTestTransport else defaultAuthTransport(),
            // 刷新会话跟着设置里选的官方根域名走；密码登录固定走 mypikpak.com
            refreshRoot = { runBlocking { preferences.pikpakDomainFlow.first() } },
        )
        val clientManager = PikoClientManager(authBroker, appScope)
        val mediaRepository = PikoMediaRepository(clientManager, preferences)
        val services = PikoServices(
            platformPreferences = preferences,
            clientManager = clientManager,
            mediaRepository = mediaRepository,
            downloadManager = PikoDownloadCoordinator(
                clientManager,
                preferences,
                IosDownloadStorage(downloads),
                appScope,
                // 片段下载在桌面上靠一个只有 JVM 版的 MP4 库无损切片，iOS 上没有对应物，不给
                segmentDownloader = null,
                mediaRepository = mediaRepository,
            ),
            uploadSources = IosUploadSources(),
            cacheStore = FilePikoCacheStore(IosPaths.cache),
        )
        val motionScale = PikoMotionScale()
        motionScale.appReduced = runBlocking { preferences.reduceMotionFlow.first() }
        appScope.launch { preferences.reduceMotionFlow.collect { motionScale.appReduced = it } }
        val platform = IosPikoPlatform(native, downloads, motionScale)
        val preview = PreviewRuntime(
            settings = appSettings,
            services = services,
            cacheDirectory = IosPaths.thumbnails,
            tempDirectory = "${IosPaths.temp}/thumbs",
            newGrabber = { IosThumbnailPlatform.newGrabber() },
            fileLength = IosFiles::length,
        )
        val appearance = preferences.appearanceFlow(platform.supportsDynamicColor)
        runtime = Runtime(native, services, platform, preview, appearance, runBlocking { appearance.first() })
        if (uiTest) {
            appScope.launch {
                delay(3_000)
                PikoLog.i("UiTest", "用假令牌登录")
                clientManager.loginWithToken("uitest@example.invalid", "uitest-refresh".toCharArray())
                    .onSuccess { PikoLog.i("UiTest", "已登录，进主界面") }
                    .onFailure { PikoLog.w("UiTest", "假登录失败", it) }
            }
        }
    }

    private val uiTestTransport = PikPakAuthClient.Transport { uri, _, _ ->
        val body = if (uri.path.endsWith("captcha/init")) {
            """{"captcha_token":"uitest"}"""
        } else {
            """{"access_token":"uitest-not-a-real-token","refresh_token":"uitest-refresh","expires_in":7200,"sub":"uitest"}"""
        }
        PikPakAuthClient.Reply(200, body)
    }

    /** 整个界面。交给窗口当根视图控制器。 */
    fun mainViewController(): UIViewController {
        val current = runtime ?: error("先调 PikSeekIos.start")
        return ComposeUIViewController {
            val appearance by current.appearance.collectAsState(current.initialAppearance)
            val videoPlayer = remember {
                VideoPlayerHost.InApp { request, onClose ->
                    IosPlayerScreen(request, current.services, current.preview, current.native, onClose)
                }
            }
            CompositionLocalProvider(
                LocalPikSeek provides current.preview.environment,
                LocalPreviewPacks provides current.preview.environment.previewPacks,
            ) {
                PikoApp(
                    services = current.services,
                    platform = current.platform,
                    appearance = appearance,
                    videoPlayer = videoPlayer,
                )
                CrashNotice(current.native)
            }
        }
    }

    /**
     * 自检页，见 [IosSelfTestScreen]。[sample] 与 [otherSample] 是随包的两个样片，结果写到 [resultPath]。
     */
    fun selfTestViewController(sample: String, otherSample: String, resultPath: String): UIViewController {
        val current = runtime ?: error("先调 PikSeekIos.start")
        return ComposeUIViewController { IosSelfTestScreen(current.native, sample, otherSample, resultPath) }
    }

    /** 日志在程序沙盒的 Application Support/PikSeek/logs 下，只留在本机。 */
    private fun installLog(native: NativeServices) {
        PikoLog.install(IosPaths.logs) { level, tag, message, error ->
            if (level >= LogLevel.WARN) println("PikSeek/$tag: $message" + (error?.let { "\n" + it.stackTraceToString() } ?: ""))
        }
        val device = UIDevice.currentDevice
        PikoLog.i("App", "启动 PikSeek ${native.appVersion()}，${device.systemName} ${device.systemVersion}，${device.model}")
    }

    private fun documentsDirectory(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: error("找不到 Documents 目录")
}
