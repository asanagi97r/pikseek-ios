package dev.pikseek.desktop

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.ui.PikoServices
import dev.pikseek.platform.AppPaths
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.ThumbnailDensity
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.MpvFrameGrabber
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.ThumbnailSource
import dev.pikseek.thumbnail.TsSliceSource
import dev.pikseek.ui.PikSeekEnvironment
import dev.pikseek.ui.PreviewCacheControl
import dev.pikseek.ui.player.WebpSpriteCodec
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PikSeek 在 Piko 之上加的那部分的进程级对象：设置、缩略图引擎与它的缓存。
 * 入口建一份，播放窗口向它要缩略图会话，设置页经 [environment] 读写设置与缓存。
 */
class PikSeekRuntime(val settings: AppSettings, private val services: PikoServices) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ThumbnailCache(AppPaths.thumbnails, WebpSpriteCodec)
    private val engine = ThumbnailEngine(cache)

    /** 程序自带的 libmpv 所在的目录，与主播放器用的是同一份。没有时（开发时资源没备好）做不了预览。 */
    private val mpvDirectory: Path? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "mpv") }
        ?.takeIf { File(it, "libmpv-2.dll").isFile }
        ?.toPath()

    val environment = PikSeekEnvironment(
        settings = settings,
        previewCache = object : PreviewCacheControl {
            override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { cache.totalBytes() }

            override suspend fun clear(): Long = withContext(Dispatchers.IO) { cache.clear() }

            override suspend fun trim(limitBytes: Long) {
                withContext(Dispatchers.IO) { cache.trim(limitBytes) }
            }
        },
    )

    init {
        // 启动时按上限清理一次，不挡启动
        scope.launch { runCatching { cache.trim(settings.thumbnailCacheLimit.value) } }
    }

    val canGenerate: Boolean get() = mpvDirectory != null

    /**
     * 为正在播放的视频开一个缩略图会话。**调用方要等主播放器出了第一帧再调。**
     *
     * 缓存齐全时不建解码器、不查网盘、不发任何请求；缺的才去生成：
     * 网盘上的视频先用低清晰度的转码流（切片取帧），用不了再退到原画（按索引定位）；本机副本直接读文件。
     *
     * @param info 网盘视频的信息；本机副本为 null
     * @param localPath 本机副本的路径；网盘视频为 null
     */
    fun openSession(
        fileId: String,
        info: PlayableMediaInfo?,
        localPath: String?,
        durationMs: Long,
        position: () -> Long,
        busy: StateFlow<Boolean>,
        parallelism: StateFlow<Int>,
    ): ThumbnailEngine.Session? {
        val directory = mpvDirectory ?: return null
        if (durationMs <= 0) return null
        // 时长取整到秒：同一个文件原画与转码读出的时长会差几十毫秒，不该因此认成两个视频
        val roundedDuration = durationMs / 1000 * 1000
        if (roundedDuration <= 0) return null
        val fingerprint = when {
            localPath != null -> MediaFingerprint(fileId.ifBlank { "local:$localPath" }, info?.gcid.orEmpty(), File(localPath).length(), roundedDuration)
            info != null -> MediaFingerprint(fileId, info.gcid, info.sizeBytes, roundedDuration)
            else -> return null
        }
        val density = when (settings.thumbnailDensity.value) {
            ThumbnailDensity.Low -> PreviewDensity.Low
            ThumbnailDensity.Medium -> PreviewDensity.Medium
            ThumbnailDensity.High -> PreviewDensity.High
        }
        val session = engine.open(
            fingerprint = fingerprint,
            density = density,
            sources = { openSources(directory, fileId, localPath, roundedDuration) },
            position = position,
            busy = busy,
            parallelism = parallelism,
        )
        // 做完之后按上限清理别的视频的缓存，正在看的这个不删
        scope.launch {
            session.join()
            runCatching { cache.trim(settings.thumbnailCacheLimit.value, keep = fingerprint) }
        }
        return session
    }

    private suspend fun openSources(directory: Path, fileId: String, localPath: String?, durationMs: Long): List<ThumbnailSource> {
        val resources = ArrayList<AutoCloseable>()
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) resources.asReversed().forEach { runCatching { it.close() } }
        }
        try {
            val grabber = withContext(Dispatchers.IO) { MpvFrameGrabber(directory) }
            resources += grabber
            val sources = if (localPath != null) {
                listOf<ThumbnailSource>(SeekingSource(localPath, grabber, "本机文件"))
            } else {
                val streams = services.mediaRepository.openThumbnailStreams(fileId, durationMs).getOrThrow()
                resources += streams
                buildList {
                    streams.transcode?.let { add(TsSliceSource(it.reader, durationMs, grabber, AppPaths.temp.resolve("thumbs"), it.label)) }
                    streams.originalUrl?.let { add(SeekingSource(it, grabber, "原画")) }
                }
            }
            PikoLog.i(TAG, "预览来源：${sources.joinToString { it.description }.ifEmpty { "无" }}")
            if (sources.isEmpty()) release()
            // 引擎关会话时会关每个来源：解码器与读取句柄是它们共用的，关一次就够
            return sources.map { Owned(it, ::release) }
        } catch (e: Throwable) {
            release()
            PikoLog.w(TAG, "预览来源打不开：${e::class.simpleName}")
            throw e
        }
    }

    private class Owned(private val delegate: ThumbnailSource, private val release: () -> Unit) : ThumbnailSource {
        override val description: String get() = delegate.description
        override val maxParallel: Int get() = delegate.maxParallel
        override val networkBytes: Long get() = delegate.networkBytes

        override suspend fun frameNear(timeMs: Long): ThumbnailFrame? = delegate.frameNear(timeMs)

        override fun close() {
            delegate.close()
            release()
        }
    }

    private companion object {
        const val TAG = "Preview"
    }
}
