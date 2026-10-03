package dev.pikseek.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.desktop.DesktopPikoDownloadStorage
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopPikoPreferences
import dev.piko.desktop.DesktopPikoUploadSources
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode
import dev.pikseek.auth.AuthBroker
import dev.pikseek.auth.DpapiCredentialStore
import dev.pikseek.auth.PikPakAuthClient
import dev.pikseek.platform.AppSettings
import dev.pikseek.security.NetworkAudit
import dev.pikseek.ui.LocalPikSeek
import dev.pikseek.ui.PikSeekEnvironment
import dev.pikseek.ui.PreviewCacheControl
import dev.pikseek.ui.auth.SignInScreen
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import dev.pikseek.ui.PreviewPackControl
import dev.pikseek.ui.preview.CloudPack
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewJobsState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * 新写的两处界面真的排出来看一眼：登录页，以及设置里的「播放预览」与「安全与隐私」。
 * 服务对象照正式入口那样拼，只是认证服务换成进程内的假应答，不联网、不登录。
 */
@OptIn(ExperimentalTestApi::class)
class PikSeekScreensTest {
    private val directory = Files.createTempDirectory("pikseek-screens")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shots = File(System.getProperty("pikseek.shots.dir")).apply { mkdirs() }

    private val settings = DesktopSettingsStore(directory.resolve("settings.properties").toFile())
    private val preferences = DesktopPikoPreferences(settings)
    private val platform = DesktopPikoPlatform(settings)
    private val appSettings = AppSettings(directory.resolve("pikseek.properties"))
    private val broker = AuthBroker(
        DpapiCredentialStore(directory.resolve("auth")),
        PikPakAuthClient.Transport { _, _, _ -> PikPakAuthClient.Reply(503, "{}") },
    )
    private val services: PikoServices = run {
        val clientManager = PikoClientManager(broker, scope)
        val media = PikoMediaRepository(clientManager, preferences)
        PikoServices(
            platformPreferences = preferences,
            clientManager = clientManager,
            mediaRepository = media,
            downloadManager = PikoDownloadCoordinator(clientManager, preferences, DesktopPikoDownloadStorage { settings.downloadDirectory }, scope, mediaRepository = media),
            uploadSources = DesktopPikoUploadSources(),
        )
    }
    private val environment = PikSeekEnvironment(
        appSettings,
        object : PreviewCacheControl {
            override suspend fun totalBytes(): Long = 37L * 1024 * 1024
            override suspend fun clear(): Long = 0
            override suspend fun trim(limitBytes: Long) = Unit
        },
        object : PreviewPackControl {
            override val packs = MutableStateFlow(emptyMap<String, CloudPack>())
            override val live = MutableStateFlow(emptyMap<String, Float>())
            override val jobs = MutableStateFlow(PreviewJobsState())
            override fun refresh() = Unit
            override fun start(request: PreviewCacheRequest) = Unit
            override fun cancel() = Unit
        },
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        directory.toFile().deleteRecursively()
    }

    @Composable
    private fun Shell(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalPikoServices provides services,
            LocalPikoPlatform provides platform,
            LocalPikSeek provides environment,
        ) {
            PikoTheme(appearance = Appearance(mode = ThemeMode.LIGHT), content = content)
        }
    }

    @Test
    fun `the sign in page says where the password goes and how the session is stored`() = runComposeUiTest {
        setContent { Shell { SignInScreen() } }
        waitForIdle()
        // 标题与按钮都写着「登录」
        assertTrue(onAllNodesWithText("登录").fetchSemanticsNodes().size >= 2)
        onNodeWithText("密码只发往 PikPak 官方认证服务（user.mypikpak.com），不保存。").assertExists()
        onNodeWithText("Windows DPAPI", substring = true).assertExists()
        save("sign-in.png")

        // 换成刷新令牌登录：说明跟着换
        onNodeWithText("刷新令牌").performClick()
        waitForIdle()
        onNodeWithText("刷新令牌只发往 PikPak 官方认证服务，用来换取会话。").assertExists()
    }

    @Test
    fun `settings carry the preview and security sections with live facts`() = runComposeUiTest {
        NetworkAudit.record("user.mypikpak.com", "Auth：密码登录")
        NetworkAudit.record("api-drive.mypikpak.com")
        NetworkAudit.record("dl-a10b-0621.mypikpak.com")
        setContent { Shell { SettingsScreen(onBackClick = null, showAbout = true) } }
        waitForIdle()

        onNodeWithText("时间轴预览").performScrollTo()
        waitForIdle()
        onNodeWithText("拖动进度条时跳转").assertExists()
        onNodeWithText("预览缓存上限").assertExists()
        save("settings-preview.png")

        onNodeWithText("网络审计（本次运行）").performScrollTo()
        waitForIdle()
        // 当场查出来的事实
        onNodeWithText("从不保存。只在登录那一刻发往 PikPak 认证服务").assertExists()
        assertTrue(onAllNodesWithText("Windows DPAPI（当前用户）加密后存于数据目录", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("无。类路径上没有这类库").assertExists()
        onNodeWithText("api-drive.mypikpak.com").assertExists()
        save("settings-security.png")

        onNodeWithText("导出安全报告").performScrollTo()
        waitForIdle()
        save("settings-security-2.png")
        onNodeWithText("原项目 Piko").performScrollTo()
        waitForIdle()
        save("settings-about.png")
    }

    private fun ComposeUiTest.save(name: String) {
        val image = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
        File(shots, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
