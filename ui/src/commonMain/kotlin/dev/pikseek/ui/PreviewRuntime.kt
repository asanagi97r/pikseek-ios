package dev.pikseek.ui

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.ui.PikoServices
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.ThumbnailDensity
import dev.pikseek.thumbnail.FrameGrabber
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.ThumbnailSource
import dev.pikseek.thumbnail.TsSliceSource
import dev.pikseek.ui.player.WebpSpriteCodec
import dev.pikseek.ui.preview.PreviewCacheJobs
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewCloud
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PikSeek 在 Piko 之上加的那部分的进程级对象：设置、缩略图引擎与它的缓存。
 * 入口建一份，播放页向它要缩略图会话，设置页经 [environment] 读写设置与缓存。桌面与 iOS 共用，
 * 各端只给出目录、怎么建解码器、怎么量本机文件的大小。
 *
 * @param cacheDirectory 预览缓存的根目录
 * @param tempDirectory 切出来的单帧小文件临时放哪
 * @param newGrabber 建一个取帧解码器；这个平台眼下建不出来时返回 null（做不了预览）。在后台线程上调
 * @param fileLength 本机文件的字节数，读不到为 0
 */
class PreviewRuntime(
    val settings: AppSettings,
    private val services: PikoServices,
    cacheDirectory: String,
    private val tempDirectory: String,
    private val newGrabber: () -> FrameGrabber?,
    private val fileLength: (String) -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ThumbnailCache(cacheDirectory, WebpSpriteCodec)
    private val engine = ThumbnailEngine(cache)
    private val cloud = PreviewCloud(services)
    private val jobs = PreviewCacheJobs(
        scope = scope,
        cloud = cloud,
        cache = cache,
        engine = engine,
        listFolder = { services.driveRepository.listAllFiles(it).getOrThrow() },
        openSources = { fileId, durationMs -> openSources(fileId, null, durationMs) },
    )

    val environment = PikSeekEnvironment(
        settings = settings,
        previewCache = object : PreviewCacheControl {
            override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { cache.totalBytes() }

            override suspend fun clear(): Long = withContext(Dispatchers.IO) { cache.clear() }

            override suspend fun trim(limitBytes: Long) {
                withContext(Dispatchers.IO) { cache.trim(limitBytes) }
            }
        },
        previewPacks = object : PreviewPackControl {
            override val packs = cloud.packs
            override val live = this@PreviewRuntime.jobs.live
            override val jobs = this@PreviewRuntime.jobs.state

            override fun refresh() {
                scope.launch { cloud.refresh() }
            }

            override fun start(request: PreviewCacheRequest) = this@PreviewRuntime.jobs.enqueue(request)

            override fun cancel() = this@PreviewRuntime.jobs.cancel()
        },
    )

    init {
        // 启动时按上限清理一次，不挡启动
        scope.launch { runCatching { cache.trim(settings.thumbnailCacheLimit.value) } }
    }

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
        if (durationMs <= 0) return null
        // 时长取整到秒：同一个文件原画与转码读出的时长会差几十毫秒，不该因此认成两个视频
        val roundedDuration = durationMs / 1000 * 1000
        if (roundedDuration <= 0) return null
        val fingerprint = when {
            localPath != null -> MediaFingerprint(fileId.ifBlank { "local:$localPath" }, info?.gcid.orEmpty(), fileLength(localPath), roundedDuration)
            info != null -> MediaFingerprint(fileId, info.gcid, info.sizeBytes, roundedDuration)
            else -> return null
        }
        val density = when (settings.thumbnailDensity.value) {
            ThumbnailDensity.Low -> PreviewDensity.Low
            ThumbnailDensity.Medium -> PreviewDensity.Medium
            ThumbnailDensity.High -> PreviewDensity.High
        }
        val gcid = fingerprint.contentHash
        val session = engine.open(
            fingerprint = fingerprint,
            density = density,
            sources = { openSources(fileId, localPath, roundedDuration) },
            position = position,
            busy = busy,
            parallelism = parallelism,
            // 本机没有时先看网盘上有没有做好的预览包：有就取回来，一个请求顶几十上百次取帧
            prepare = {
                if (gcid.isNotBlank() && cache.stored(fingerprint) == null) {
                    cloud.lookup(gcid)?.let { pack ->
                        val bytes = cloud.download(pack)
                        val imported = withContext(Dispatchers.IO) { cache.importPack(fingerprint, bytes) }
                        PikoLog.i(TAG, "预览取自网盘上的预览包：${imported?.frameCount ?: 0}/${imported?.slotCount ?: 0} 格，${bytes.size / 1024} KB")
                    }
                }
            },
        )
        // 做完之后按上限清理别的视频的缓存，正在看的这个不删
        scope.launch {
            session.join()
            runCatching { cache.trim(settings.thumbnailCacheLimit.value, keep = fingerprint) }
        }
        return session
    }

    private suspend fun openSources(fileId: String, localPath: String?, durationMs: Long): List<ThumbnailSource> {
        val owner = Resources()
        try {
            val grabber = withContext(Dispatchers.IO) { newGrabber() } ?: error("这个平台上建不出取帧解码器")
            owner += grabber
            val sources = if (localPath != null) {
                listOf<ThumbnailSource>(SeekingSource(localPath, grabber, "本机文件"))
            } else {
                val streams = services.mediaRepository.openThumbnailStreams(fileId, durationMs).getOrThrow()
                owner += streams
                buildList {
                    streams.transcode?.let { add(TsSliceSource(it.reader, durationMs, grabber, tempDirectory, it.label)) }
                    streams.originalUrl?.let { add(SeekingSource(it, grabber, "原画")) }
                }
            }
            PikoLog.i(TAG, "预览来源：${sources.joinToString { it.description }.ifEmpty { "无" }}")
            if (sources.isEmpty()) owner.release()
            // 引擎关会话时会关每个来源：解码器与读取句柄是它们共用的，关一次就够
            return sources.map { Owned(it, owner::release) }
        } catch (e: Throwable) {
            owner.release()
            PikoLog.w(TAG, "预览来源打不开：${e::class.simpleName}")
            throw e
        }
    }

    /** 几个来源共用的东西，一起关，只关一次。 */
    private class Resources {
        private val items = ArrayList<AutoCloseable>()

        @Volatile
        private var released = false

        operator fun plusAssign(item: AutoCloseable) {
            items += item
        }

        fun release() {
            if (released) return
            released = true
            items.asReversed().forEach { runCatching { it.close() } }
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
