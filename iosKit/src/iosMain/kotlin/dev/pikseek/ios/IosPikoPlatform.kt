package dev.pikseek.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.platform.DiskSpace
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.ExternalVideoPlayer
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.UploadPicker
import dev.piko.ui.platform.VideoPreviewSupport
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
import dev.pikseek.platform.IosPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController

/** 共享界面在 iOS 上的平台能力。 */
internal class IosPikoPlatform(
    private val native: NativeServices,
    private val downloadDirectory: String,
    override val motionScale: PikoMotionScale = PikoMotionScale(),
) : PikoPlatform {
    // 触屏设备，与 Android 同一套动效
    override val motionStyle: MotionStyle = MotionStyle.Expressive

    override val appVersion: String = native.appVersion()

    override val supportsDynamicColor: Boolean = false

    @Composable
    override fun dynamicColorScheme(dark: Boolean): ColorScheme = error("iOS 不支持系统取色，外观会落到内置主题上")

    override val supportsBlur: Boolean = true

    override val deviceSummary: String =
        "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}，${UIDevice.currentDevice.model}"

    /** 写成临时文件，交给系统的分享面板：存到「文件」、发给别的 App 都从那里走。 */
    override suspend fun exportLog(fileName: String, content: String): Boolean {
        val path = "${IosPaths.temp}/$fileName"
        if (runCatching { IosFiles.writeText(path, content) }.isFailure) return false
        val presenter = topViewController() ?: return false
        native.share(path, presenter)
        return true
    }

    override fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    @Composable
    override fun isImeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    override fun readClipboardText(): String? = UIPasteboard.generalPasteboard.string

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override val localFiles: LocalFileActions = object : LocalFileActions {
        override fun thumbnailModel(path: String): Any? = path.takeIf(IosFiles::exists)?.let { "file://$it" }

        override fun exists(path: String): Boolean = IosFiles.exists(path)

        // iOS 没有「用默认程序打开」这回事，交给分享面板，由用户挑去处
        override fun openExternally(path: String, isMedia: Boolean) = share(path, isMedia)

        override fun openContainingFolder(path: String) = share(path, false)

        override val canShare: Boolean = true

        override fun share(path: String, isMedia: Boolean) {
            if (!IosFiles.exists(path)) return
            topViewController()?.let { native.share(path, it) }
        }
    }

    override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker {
        override val description = "下载的文件在「文件」App 的「我的 iPhone → PikSeek → Downloads」里。iOS 上位置固定，不能改。"

        override fun displayName(storedPath: String): String = "「文件」App → 我的 iPhone → PikSeek → Downloads"

        @OptIn(ExperimentalForeignApi::class)
        override fun diskSpace(storedPath: String): DiskSpace? {
            val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(downloadDirectory, null) ?: return null
            val free = (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: return null
            val total = (attributes[NSFileSystemSize] as? NSNumber)?.longLongValue ?: return null
            return DiskSpace(freeBytes = free, totalBytes = total).takeIf { it.totalBytes > 0 }
        }

        // 位置固定，没有可选的
        @Composable
        override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit = {}
    }

    override val uploadPicker: UploadPicker = object : UploadPicker {
        @Composable
        override fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit = {
            topViewController()?.let { presenter ->
                native.pickFiles(multiple = true, from = presenter) { paths -> if (paths.isNotEmpty()) onPicked(paths) }
            }
        }

        // 系统的文件选择器只在少数来源下能选整个文件夹，先不做
        @Composable
        override fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit = {}
    }

    override val externalPlayer: ExternalVideoPlayer? = null

    override val videoPreview: VideoPreviewSupport = IosVideoPreview(native)

    override val linkAssociation: LinkAssociation? = null

    @Composable
    override fun FullscreenDialog(
        onDismiss: () -> Unit,
        immersive: Boolean,
        systemBarsVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }

    // 触屏靠甩动，不画滚动条
    @Composable
    override fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

    @Composable
    override fun ListScrollbar(state: LazyGridState, modifier: Modifier) = Unit
}

/** 眼下最上面的那个视图控制器，系统面板从它弹出。 */
internal fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) controller = controller.presentedViewController ?: return controller
}

/** 建不出播放器时顶上的预览后端：什么都不播，界面不至于崩。 */
internal object MissingPreviewBackend : PreviewBackend {
    override val positionMillis: Long = 0
    override val durationMillis: Long = 0
    override val bufferedPositionMillis: Long = 0
    override val isPlaying: Boolean = false
    override val isBuffering: Boolean = false
    override val speed: Float = 1f
    override val supportsSpeed: Boolean = false
    override val aspectRatio: PlayerAspectRatio? = null
    override val videoAspect: Float? = null
    override val volume: Float? = null
    override val events: Flow<PlaybackBackendEvent> = emptyFlow()

    override suspend fun open(target: PlaybackTarget, startMillis: Long, playWhenReady: Boolean, subtitles: List<ExternalSubtitle>) =
        error("这台设备上建不出播放器")

    override fun stop() = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMillis: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun setAspectRatio(mode: PlayerAspectRatio) = Unit

    override fun setVolume(volume: Float) = Unit

    override fun release() = Unit

    override fun setBufferAhead(seconds: Int) = Unit

    override fun bufferReport(): String? = null
}
