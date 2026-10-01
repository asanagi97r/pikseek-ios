package dev.pikseek.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.VideoPreviewSupport
import dev.pikseek.player.MpvPlaybackBackend
import kotlinx.coroutines.CoroutineScope

/**
 * 一个播放器：Swift 一侧的 libmpv 实例（带画面视图）加上架在它上面的共用播放后端。
 * 关的时候只关一次，由建它的组合在离开时调 [close]。
 */
internal class IosPlayer(val bridge: MpvPlayerBridge, scope: CoroutineScope) {
    // 苹方简繁都全，系统自带：字幕里缺字时落到它
    val backend = MpvPlaybackBackend(MpvHandleAdapter(bridge), scope, subtitleFont = "PingFang SC")

    fun close() = backend.close()
}

/** 建一个播放器，离开组合时关掉。这个设备上建不出 libmpv 实例时为 null。 */
@Composable
internal fun rememberIosPlayer(native: NativeServices): IosPlayer? {
    val scope = rememberCoroutineScope()
    val player = remember(native) { native.createPlayer()?.let { IosPlayer(it, scope) } }
    DisposableEffect(player) { onDispose { player?.close() } }
    return player
}

/**
 * 视频画面。视图不接触摸：点击、双击、拖动都由叠在上面的 Compose 控件层处理。
 */
@Composable
internal fun IosPlayerSurface(player: IosPlayer, modifier: Modifier) {
    UIKitView(
        factory = { player.bridge.view() },
        modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
    )
}

/** 片段下载面板与随机片段里的画面预览：一个缓存小的播放器。 */
internal class IosVideoPreview(private val native: NativeServices) : VideoPreviewSupport {
    @Composable
    override fun rememberPreviewBackend(keyframeStart: Boolean): PreviewBackend {
        val player = rememberIosPlayer(native)
        return remember(player) {
            if (player == null) {
                MissingPreviewBackend
            } else {
                // 往后少囤：同时开着几个预览播放器时，各自往后读几百 MB 会把账号的连接占满
                player.bridge.setProperty("demuxer-max-bytes", "${8 * MIB}")
                player.bridge.setProperty("demuxer-max-back-bytes", "${4 * MIB}")
                player.bridge.setProperty("cache-secs", "$PREVIEW_CACHE_SECONDS")
                player.bridge.setProperty("demuxer-lavf-probesize", "$PREVIEW_PROBE_BYTES")
                player.bridge.setProperty("demuxer-lavf-analyzeduration", "1")
                if (keyframeStart) player.bridge.setProperty("hr-seek", "no")
                Preview(player)
            }
        }
    }

    @Composable
    override fun Surface(backend: PreviewBackend, modifier: Modifier) {
        (backend as? Preview)?.let { IosPlayerSurface(it.player, modifier) }
    }

    private class Preview(val player: IosPlayer) : PreviewBackend, PlaybackBackend by player.backend {
        // 播放器本身由 rememberIosPlayer 在离开组合时关闭，这里只停播
        override fun release() = player.backend.stop()

        override fun setBufferAhead(seconds: Int) = player.backend.setBufferAhead(seconds)

        override fun bufferReport(): String? =
            "cache-secs=${player.bridge.getProperty("cache-secs")}，缓存 ${player.bridge.getProperty("demuxer-cache-duration")} 秒"
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val PREVIEW_CACHE_SECONDS = 8
        const val PREVIEW_PROBE_BYTES = 1024 * 1024
    }
}
