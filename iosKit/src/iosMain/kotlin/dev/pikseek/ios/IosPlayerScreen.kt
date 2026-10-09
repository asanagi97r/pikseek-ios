package dev.pikseek.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.ui.PikoServices
import dev.piko.ui.navigation.Screen
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTopBar
import dev.piko.ui.screens.player.playlistOf
import dev.piko.ui.screens.player.rememberPlayerFileActions
import dev.piko.ui.screens.player.siblingMedia
import dev.pikseek.performance.PerformanceMetrics
import dev.pikseek.ui.PreviewRuntime
import dev.pikseek.ui.player.LocalTimelinePreview
import dev.pikseek.ui.player.LocalPlaybackSettings
import dev.pikseek.ui.player.PerformanceOverlay
import dev.pikseek.ui.player.TimelinePreview
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

/**
 * 应用内的全屏播放页。接线与桌面版的播放窗口相同（取流、换集、时间轴预览、性能浮层），
 * 只是画面来自 iOS 的 libmpv，没有窗口、键盘与鼠标那些事。横竖屏跟着设备转。
 */
@Composable
internal fun IosPlayerScreen(
    request: Screen.VideoPlayer,
    services: PikoServices,
    preview: PreviewRuntime,
    native: NativeServices,
    onClose: () -> Unit,
) {
    val player = rememberIosPlayer(native)
    if (player == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerTheme {
                PlayerTopBar(title = request.fileName, episodeLabel = null, isLocalPlayback = false, onBackClick = onClose, modifier = Modifier.align(Alignment.TopCenter))
                Text("这台设备上建不出播放器", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val backend = player.backend
    val downloads = services.downloadManager
    var siblingVideos by remember(request) { mutableStateOf(emptyList<FileStat>()) }
    val state = remember(request, player) {
        PlayerScreenState(
            repository = services.mediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = request.fileId,
            initialFileName = request.fileName,
            initialLocalPath = request.localPath,
            initialStartMillis = request.startMillis,
            // 本地副本按文件长度验完整性，需要对应的 FileStat，从同目录列表里取
            resolveLocalPath = { fileId, hint ->
                hint?.takeIf(IosFiles::exists)
                    ?: siblingVideos.find { it.id == fileId }
                        ?.let { downloads.findCompletedLocalPath(it) }
                        ?.takeIf(IosFiles::exists)
            },
        )
    }
    val volume = remember(backend) { BackendVolume(backend) }
    val brightness = remember { ScreenBrightness() }
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsState(initial = true)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(request) {
        val siblings = services.driveRepository.siblingMedia(request.fileId)
        siblingVideos = siblings.videos
        state.playlist = playlistOf(siblingVideos, services.preferences, siblings.subtitles)
    }

    // 同目录元数据到了之后再验一次磁盘，下好的片子从当前位置换到本地文件
    LaunchedEffect(state.fileId, siblingVideos) {
        if (state.isLocalPlayback) return@LaunchedEffect
        val stat = siblingVideos.find { it.id == state.fileId } ?: return@LaunchedEffect
        downloads.findCompletedLocalPath(stat)?.takeIf(IosFiles::exists)?.let(state::useLocalCopy)
    }

    // ---- 时间轴预览 ----
    val appSettings = preview.settings
    val previewEnabled by appSettings.thumbnailsEnabled.collectAsState()
    val previewDensity by appSettings.thumbnailDensity.collectAsState()
    val showPerformance by appSettings.performanceOverlay.collectAsState()
    val prefetchNext by appSettings.prefetchNext.collectAsState()
    val timelinePreview = remember(state) { TimelinePreview(dragSeekMode = { appSettings.dragSeekMode.value }, seek = state::seekTo) }
    // 顶栏的收藏按钮作用于正在播的这一集；同目录列表到了才对得上号
    LaunchedEffect(timelinePreview, state.fileId, siblingVideos) {
        timelinePreview.ratingFile = siblingVideos.find { it.id == state.fileId }
    }
    LaunchedEffect(state, prefetchNext) { state.prefetchNext = prefetchNext }
    // 连续播放与播放顺序：底栏按钮与播放设置里改的都是这份设置，换了马上生效
    LaunchedEffect(state) { appSettings.continuousPlay.collect { state.continuousPlay = it } }
    LaunchedEffect(state) { appSettings.playOrder.collect { state.playOrder = it } }
    // 主播放器要带宽的时候（起播、缓冲、拖动后）缩略图让路；缓冲得够多或暂停着时才开第二路
    val playerBusy = remember(state) { snapshotFlow { state.needsBandwidth }.stateIn(scope, SharingStarted.Eagerly, true) }
    val previewWorkers = remember(state) {
        snapshotFlow { if (!state.isPlaying || state.bufferedPositionMillis - state.positionMillis >= PREVIEW_SECOND_WORKER_BUFFER_MS) 2 else 1 }
            .stateIn(scope, SharingStarted.Eagerly, 1)
    }
    // 出了第一帧的是哪一集。换清晰度、重连时画面会重开，但集没换，预览不必重来
    var startedFileId by remember(state) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.fileId, state.hasFirstFrame) {
        if (state.hasFirstFrame) startedFileId = state.fileId
    }
    val previewReady = previewEnabled && !state.isImage && startedFileId == state.fileId && state.durationMillis > 0
    // 顺序是硬规矩：取流 → 交给播放器 → 第一帧 → 这里才开始。预览的任何准备都不在第一帧之前
    LaunchedEffect(state.fileId, previewReady, previewDensity) {
        timelinePreview.reset()
        if (!previewReady) return@LaunchedEffect
        val session = preview.openSession(
            fileId = state.fileId,
            info = state.mediaInfo,
            localPath = request.localPath?.takeIf { state.isLocalPlayback },
            durationMs = state.durationMillis,
            position = { state.positionMillis },
            busy = playerBusy,
            parallelism = previewWorkers,
        ) ?: return@LaunchedEffect
        timelinePreview.session = session
        timelinePreview.saveMarks = preview::saveMarks
        timelinePreview.autoSkip = { appSettings.autoSkipIntro.value }
        try {
            // 进度条分段：读网盘上的；没有就等预览做齐后在后台做场景分点（与桌面同一套）
            launch {
                preview.followMarks(
                    fileId = state.fileId,
                    info = state.mediaInfo,
                    localPath = request.localPath?.takeIf { state.isLocalPlayback },
                    durationMs = state.durationMillis,
                    session = session,
                    busy = playerBusy,
                    onMarks = { timelinePreview.marks = it },
                )
            }
            // 跳到了别处：把那附近的缩略图提前做
            launch {
                var last = state.positionMillis
                snapshotFlow { state.positionMillis }.collect { position ->
                    if (abs(position - last) > PREVIEW_JUMP_MS) session.prefer(position)
                    last = position
                }
            }
            session.progress.collect { progress ->
                timelinePreview.progress = progress
                PerformanceMetrics.update {
                    it.copy(
                        thumbnailCoarseDone = progress.coarseDone,
                        thumbnailCoarseTotal = progress.coarseTotal,
                        thumbnailFullDone = progress.fullDone,
                        thumbnailFullTotal = progress.fullTotal,
                        thumbnailNetworkBytes = progress.networkBytes,
                        previewCacheBytes = progress.cacheBytes,
                        thumbnailState = "${progress.state.name} · ${progress.source}",
                    )
                }
            }
        } finally {
            session.close()
        }
    }
    if (showPerformance) {
        LaunchedEffect(backend) {
            while (true) {
                PerformanceMetrics.update { it.copy(droppedFrames = backend.droppedFrames()) }
                delay(1_000)
            }
        }
    }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 播放时不让屏幕自己熄；暂停与离开时恢复
    DisposableEffect(state.isPlaying) {
        UIApplication.sharedApplication.idleTimerDisabled = state.isPlaying
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.isImage) {
            AsyncImage(model = state.mediaInfo?.currentUrl, contentDescription = state.title, modifier = Modifier.fillMaxSize())
            PlayerTheme {
                PlayerTopBar(
                    title = state.title,
                    episodeLabel = null,
                    isLocalPlayback = state.isLocalPlayback,
                    onBackClick = onClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        } else {
            IosPlayerSurface(player, Modifier.fillMaxSize())

            CompositionLocalProvider(LocalTimelinePreview provides timelinePreview, LocalPlaybackSettings provides appSettings) {
                MobilePlayerControls(
                    title = state.title,
                    isLocalPlayback = state.isLocalPlayback,
                    isPlaying = state.isPlaying,
                    isLoading = state.isLoading,
                    positionMillis = state.positionMillis,
                    durationMillis = state.durationMillis,
                    bufferedPositionMillis = state.bufferedPositionMillis,
                    playbackSpeed = state.playbackSpeed,
                    aspectRatio = state.aspectRatio,
                    qualityOptions = state.qualityOptions,
                    currentQuality = state.currentQuality,
                    errorMessage = state.errorMessage,
                    resumedFromMillis = state.resumedFromMillis,
                    onPlayPause = state::togglePlayPause,
                    onSeek = state::seekTo,
                    onSpeedChange = state::setSpeed,
                    onAspectRatioChange = state::setAspectRatio,
                    onQualityChange = state::selectQuality,
                    onRetry = state::retry,
                    onRestartFromBeginning = state::restartFromBeginning,
                    onBack = onClose,
                    // 播放页本来就铺满屏幕；横过来看转设备即可
                    onToggleFullscreen = {},
                    isFullscreen = false,
                    isLandscapeVideo = null,
                    playlist = state.playlist,
                    currentFileId = state.fileId,
                    hasPrevious = state.previousEntry != null,
                    hasNext = state.nextEntry != null,
                    onPrevious = state::playPrevious,
                    onNext = state::playNext,
                    onSelectEntry = state::playEntry,
                    hideEpisodeThumbnails = isSpoilerBlurEnabled,
                    audioTracks = state.audioTracks,
                    selectedAudioTrackId = state.selectedAudioTrackId,
                    onSelectAudioTrack = state::selectAudioTrack,
                    subtitleTracks = state.subtitleTracks,
                    selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                    onSelectSubtitleTrack = state::selectSubtitleTrack,
                    brightness = brightness,
                    volume = volume,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    fileActions = rememberPlayerFileActions(state.fileId, state.isLocalPlayback) { message ->
                        scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
                    },
                    rotationDegrees = state.rotationDegrees.takeIf { state.supportsRotation },
                    onRotationChange = state::setRotation,
                    onPickDriveSubtitle = { file: FileStat -> state.addDriveSubtitle(file.id, file.name) }
                        .takeIf { state.canAddSubtitle },
                )
            }
            if (showPerformance) PerformanceOverlay(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 64.dp))
        }
    }
}

// 缓冲领先这么多时，缩略图引擎可以开第二路读取
private const val PREVIEW_SECOND_WORKER_BUFFER_MS = 20_000L

// 播放位置一下变了这么多，算是跳转
private const val PREVIEW_JUMP_MS = 15_000L

/** 竖滑调的是播放器自己的音量：系统媒体音量程序改不了，用户用音量键调。 */
private class BackendVolume(private val backend: PlaybackBackend) : PlayerLevelControl {
    override fun current(): Float = backend.volume ?: 1f

    override fun set(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        backend.setVolume(clamped)
        return clamped
    }
}

/** 屏幕亮度。 */
private class ScreenBrightness : PlayerLevelControl {
    override fun current(): Float = UIScreen.mainScreen.brightness.toFloat()

    override fun set(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        UIScreen.mainScreen.brightness = clamped.toDouble()
        return clamped
    }
}
