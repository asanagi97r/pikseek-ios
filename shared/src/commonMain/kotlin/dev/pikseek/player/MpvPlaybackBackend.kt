package dev.pikseek.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MPV_SUBTITLE_LANGUAGES
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.mpvSubtitleAddCommands
import dev.piko.shared.media.player.mpvSubtitleSelectCommand
import dev.piko.shared.media.player.readMpvTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 直接架在 libmpv 上的播放后端。桌面版的后端经 MediaMP 再到 mpv；MediaMP 的 mpv 在 iOS 上没有画面输出，
 * 所以 iOS 这边绕开它，直接对 [MpvHandle] 下命令、听事件，语义与桌面的 MediampPlaybackBackend 对齐：
 *
 * - [open] 挂起到文件加载好（file-loaded）才返回，加载失败就抛异常；
 * - 第一帧出来（加载后的第一个 playback-restart）发 [PlaybackBackendEvent.Ready]，外挂字幕在这之前挂好；
 * - 播到末尾发 [PlaybackBackendEvent.Ended]（keep-open 让画面停在最后一帧，由 eof-reached 得知）；
 * - 播放途中文件被迫中止发 [PlaybackBackendEvent.Error]。
 *
 * mpv 的回调在它自己的事件线程上；这里把它们排进一个队列，在 [scope] 的调度器（界面线程）上依次处理，
 * 所以下面的状态只在界面线程上读写。
 *
 * @param subtitleFont 字幕里缺字时的后备字体，要一款简繁都全的
 */
class MpvPlaybackBackend(
    private val mpv: MpvHandle,
    scope: CoroutineScope,
    subtitleFont: String? = null,
) : PlaybackBackend {
    override var positionMillis by mutableLongStateOf(0L)
        private set
    override var durationMillis by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis by mutableLongStateOf(0L)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override var isBuffering by mutableStateOf(false)
        private set

    // 不能写成 private set 的属性：生成的 setter 与接口的 setSpeed、setAspectRatio 签名相撞
    private var currentSpeed by mutableFloatStateOf(1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean = true
    private var currentAspectRatio by mutableStateOf(PlayerAspectRatio.Fit)
    override val aspectRatio: PlayerAspectRatio get() = currentAspectRatio
    override var videoAspect by mutableStateOf<Float?>(null)
        private set
    private var currentVolume by mutableStateOf<Float?>(1f)
    override val volume: Float? get() = currentVolume

    override var audioTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var subtitleTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var selectedAudioTrackId by mutableStateOf<String?>(null)
        private set
    override var selectedSubtitleTrackId by mutableStateOf<String?>(null)
        private set

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    // ---- 以下只在界面线程上读写 ----

    /** 正在等的那次 [open]：文件加载好或失败时完成它。 */
    private var loading: CompletableDeferred<Unit>? = null

    /** 文件加载好了，还没出第一帧。 */
    private var awaitingFirstFrame = false
    private var pausedForCache = false
    private var seeking = false
    private var pendingSubtitles: List<ExternalSubtitle> = emptyList()

    /** 有文件开着（加载好、还没停）。 */
    private var hasMedia = false
    private var closed = false

    private sealed interface Signal {
        class Property(val name: String, val value: String?) : Signal
        class Event(val name: String, val detail: String?) : Signal
    }

    private val signals = Channel<Signal>(Channel.UNLIMITED)

    init {
        mpv.setProperty("slang", MPV_SUBTITLE_LANGUAGES)
        subtitleFont?.let { mpv.setProperty("sub-font", it) }
        // 播到末尾停在最后一帧，不卸载文件：结束由 eof-reached 得知，用户还能往回拖
        mpv.setProperty("keep-open", "yes")
        // 没有文件时也不退出
        mpv.setProperty("idle", "yes")
        mpv.setListener(object : MpvListener {
            override fun onPropertyChanged(name: String, value: String?) {
                signals.trySend(Signal.Property(name, value))
            }

            override fun onEvent(event: String, detail: String?) {
                signals.trySend(Signal.Event(event, detail))
            }
        })
        OBSERVED.forEach(mpv::observe)
        scope.launch {
            for (signal in signals) {
                when (signal) {
                    is Signal.Property -> onProperty(signal.name, signal.value)
                    is Signal.Event -> onEvent(signal.name, signal.detail)
                }
            }
        }
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        check(!closed) { "播放器已关闭" }
        val location = when (target) {
            is PlaybackTarget.LocalFile -> target.path
            is PlaybackTarget.Url -> target.url
        }
        // 上一次 open 还没等到结果就来了新的：让它按取消收场
        loading?.cancel()
        val waiter = CompletableDeferred<Unit>()
        loading = waiter
        awaitingFirstFrame = false
        hasMedia = false
        pendingSubtitles = subtitles
        positionMillis = startMillis.coerceAtLeast(0)
        durationMillis = 0
        bufferedPositionMillis = 0
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        refreshBuffering()

        mpv.setProperty("pause", if (playWhenReady) "no" else "yes")
        val accepted = if (startMillis > 0) {
            mpv.command(listOf("loadfile", location, "replace", "-1", "start=${seconds(startMillis)}"))
        } else {
            mpv.command(listOf("loadfile", location, "replace"))
        }
        if (!accepted) {
            loading = null
            refreshBuffering()
            throw IllegalStateException("播放器没有接受这个地址")
        }
        try {
            waiter.await()
        } catch (e: CancellationException) {
            // 调用方不等了（换集、关播放器）：半开的文件卸掉
            if (loading === waiter) {
                loading = null
                mpv.command(listOf("stop"))
                refreshBuffering()
            }
            throw e
        }
    }

    override fun selectAudioTrack(id: String) {
        mpv.setProperty("aid", id)
    }

    override fun selectSubtitleTrack(id: String?) {
        mpv.setProperty("sid", id ?: "no")
    }

    override val canAddSubtitle: Boolean get() = true

    /** 开播之后照样能挂；还在打开时排进第一帧之前的那一批。 */
    override fun addSubtitle(subtitle: ExternalSubtitle) {
        if (loading != null || awaitingFirstFrame) {
            pendingSubtitles = pendingSubtitles + subtitle
            return
        }
        mpv.command(mpvSubtitleSelectCommand(subtitle).toList())
    }

    override val supportsRotation: Boolean get() = true

    /** mpv 给的 dwidth、dheight 已按叠加后的旋转交换过宽高，[videoAspect] 跟着变。 */
    override fun setRotation(degrees: Int) {
        mpv.setProperty("video-rotate", "$degrees")
    }

    override fun stop() {
        loading?.cancel()
        loading = null
        awaitingFirstFrame = false
        hasMedia = false
        mpv.command(listOf("stop"))
        refreshBuffering()
    }

    override fun play() {
        // 停在末尾时再按播放：从头来
        if (mpv.getProperty("eof-reached") == "yes") mpv.command(listOf("seek", "0", "absolute"))
        mpv.setProperty("pause", "no")
    }

    override fun pause() {
        mpv.setProperty("pause", "yes")
    }

    override fun seekTo(positionMillis: Long) {
        val target = positionMillis.coerceAtLeast(0)
        // 读数经事件回报会慢一拍；进度条松手后要立刻停在新位置上
        this.positionMillis = target
        mpv.command(listOf("seek", seconds(target), "absolute"))
    }

    override fun setSpeed(speed: Float) {
        mpv.setProperty("speed", speed.toString())
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        when (mode) {
            PlayerAspectRatio.Fit -> {
                mpv.setProperty("keepaspect", "yes")
                mpv.setProperty("panscan", "0")
            }
            PlayerAspectRatio.Crop -> {
                mpv.setProperty("keepaspect", "yes")
                mpv.setProperty("panscan", "1")
            }
            PlayerAspectRatio.Stretch -> mpv.setProperty("keepaspect", "no")
        }
        currentAspectRatio = mode
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        mpv.setProperty("volume", (clamped * 100).toString())
        // 连着调时下一次要在这次的值上累加，先行写入
        currentVolume = clamped
        // 静音时调音量，意图是要听见
        if (clamped > 0f) mpv.setProperty("mute", "no")
    }

    /** 播放器自己往后缓冲多少秒。播放中途改，下一次读即生效。 */
    fun setBufferAhead(seconds: Int) {
        mpv.setProperty("cache-secs", "$seconds")
    }

    /** 丢了多少帧：解码跟不上丢的加上显示跟不上丢的。性能浮层用。 */
    fun droppedFrames(): Long {
        val decoder = mpv.getProperty("decoder-frame-drop-count")?.toLongOrNull() ?: 0L
        val output = mpv.getProperty("frame-drop-count")?.toLongOrNull() ?: 0L
        return decoder + output
    }

    /** 关掉并销毁 mpv 实例。 */
    fun close() {
        if (closed) return
        closed = true
        loading?.cancel()
        loading = null
        mpv.setListener(null)
        signals.close()
        mpv.close()
    }

    private fun onProperty(name: String, value: String?) {
        when (name) {
            "time-pos" -> if (!seeking) value?.toDoubleOrNull()?.let { positionMillis = (it * 1000).toLong().coerceAtLeast(0) }
            "duration" -> durationMillis = value?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            "pause" -> isPlaying = value == "no"
            "paused-for-cache" -> {
                pausedForCache = value == "yes"
                refreshBuffering()
            }
            "seeking" -> {
                seeking = value == "yes"
                refreshBuffering()
            }
            "demuxer-cache-time" -> bufferedPositionMillis = value?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            "speed" -> value?.toFloatOrNull()?.let { currentSpeed = it }
            "volume" -> value?.toFloatOrNull()?.let { currentVolume = (it / 100f).coerceIn(0f, 1f) }
            "dwidth", "dheight" -> refreshVideoAspect()
            "track-list", "aid", "sid" -> refreshTracks()
            "eof-reached" -> if (value == "yes" && hasMedia && loading == null) _events.tryEmit(PlaybackBackendEvent.Ended)
        }
    }

    private fun onEvent(event: String, detail: String?) {
        when (event) {
            "file-loaded" -> {
                val waiter = loading ?: return
                loading = null
                hasMedia = true
                awaitingFirstFrame = true
                refreshTracks()
                refreshBuffering()
                waiter.complete(Unit)
            }
            "playback-restart" -> if (awaitingFirstFrame) {
                awaitingFirstFrame = false
                attachPendingSubtitles()
                refreshBuffering()
                _events.tryEmit(PlaybackBackendEvent.Ready)
            }
            "end-file" -> {
                // 被新文件顶掉（stop、redirect）或正常放完的都不算错
                if (detail == null || !detail.startsWith("error")) return
                val reason = detail.substringAfter(':', "").ifBlank { null }
                val waiter = loading
                if (waiter != null) {
                    loading = null
                    refreshBuffering()
                    waiter.completeExceptionally(IllegalStateException(reason ?: "打不开这个视频"))
                } else if (hasMedia) {
                    hasMedia = false
                    awaitingFirstFrame = false
                    refreshBuffering()
                    _events.tryEmit(PlaybackBackendEvent.Error(reason))
                }
            }
        }
    }

    private fun refreshBuffering() {
        isBuffering = loading != null || awaitingFirstFrame || pausedForCache || seeking
    }

    private fun refreshVideoAspect() {
        val width = mpv.getProperty("dwidth")?.toIntOrNull() ?: 0
        val height = mpv.getProperty("dheight")?.toIntOrNull() ?: 0
        videoAspect = if (width > 0 && height > 0) width.toFloat() / height else null
    }

    private fun refreshTracks() {
        val snapshot = readMpvTracks(mpv::getProperty)
        audioTracks = snapshot.audio
        subtitleTracks = snapshot.subtitles
        selectedAudioTrackId = snapshot.selectedAudioId
        selectedSubtitleTrackId = snapshot.selectedSubtitleId
    }

    private fun attachPendingSubtitles() {
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        if (subtitles.isEmpty()) return
        val hasSelected = readMpvTracks(mpv::getProperty).selectedSubtitleId != null
        mpvSubtitleAddCommands(subtitles, hasSelected).forEach { mpv.command(it.toList()) }
    }

    /** 毫秒写成 mpv 认的秒数，三位小数，小数点不随系统语言变。 */
    private fun seconds(millis: Long): String = "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"

    private companion object {
        val OBSERVED = listOf(
            "time-pos", "duration", "pause", "paused-for-cache", "seeking", "demuxer-cache-time",
            "speed", "volume", "dwidth", "dheight", "track-list", "aid", "sid", "eof-reached",
        )
    }
}
