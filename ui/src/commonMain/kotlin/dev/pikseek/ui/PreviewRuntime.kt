package dev.pikseek.ui

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.ui.PikoServices
import dev.pikseek.platform.AppSettings
import dev.pikseek.platform.ThumbnailDensity
import dev.pikseek.thumbnail.AudioDecoder
import dev.pikseek.thumbnail.EpisodeMatcher
import dev.pikseek.thumbnail.EpisodeSound
import dev.pikseek.thumbnail.FrameGrabber
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.MediaMarks
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.SceneAnalysis
import dev.pikseek.thumbnail.SeekingSource
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailFrame
import dev.pikseek.thumbnail.ThumbnailSource
import dev.pikseek.thumbnail.ThumbnailState
import dev.pikseek.thumbnail.TsSliceSource
import dev.pikseek.ui.player.WebpSpriteCodec
import dev.pikseek.ui.preview.PreviewCacheJobs
import dev.pikseek.ui.preview.PreviewCacheRequest
import dev.pikseek.ui.preview.PreviewCloud
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.pikseek.ui.rating.FileRatings
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * @param newAudioDecoder 建一个解声音的解码器，认片头片尾用；这个平台解不了时为 null（不认片头片尾）
 */
class PreviewRuntime(
    val settings: AppSettings,
    private val services: PikoServices,
    cacheDirectory: String,
    private val tempDirectory: String,
    private val newGrabber: () -> FrameGrabber?,
    private val fileLength: (String) -> Long,
    private val newAudioDecoder: (() -> AudioDecoder?)? = null,
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
        probeDurationMs = ::probeDurationMs,
        episodeAudio = if (newAudioDecoder != null) ::episodeAudio else null,
    )

    /** 收藏与讨厌，网盘页、播放器经 LocalFileRatings 取用。状态在主线程上改。 */
    val ratings = FileRatings(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        store = cloud,
        setStarred = services.driveRepository::setStarred,
        accounts = services.clientManager.currentClient.map { it?.account },
    )

    // 这次运行里读过、改过的分段：gcid → 最新的一份。改分段与后台做完的分点都先落到这里，再传到网盘
    private val marksLock = Mutex()
    private val knownMarks = HashMap<String, MediaMarks>()

    /**
     * 网盘的文件列表里没给时长时（有些 m3u8 就这样）再找：先查文件详情（各画质版本各带着时长），
     * 再不行打开原画让解码器读。毫秒；都拿不到为 0。
     */
    private suspend fun probeDurationMs(fileId: String): Long {
        val detail = services.driveRepository.getFileDetail(fileId).getOrNull()
        val fromDetail = detail?.let { d ->
            d.params["duration"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
                ?: d.medias.mapNotNull { it.video?.duration?.takeIf { seconds -> seconds > 0 } }.maxOrNull()?.let { it * 1000 }
        } ?: 0L
        if (fromDetail > 0) {
            PikoLog.i(TAG, "列表里没有时长，取自文件详情")
            return fromDetail
        }
        return withContext(Dispatchers.IO) {
            val grabber = newGrabber() ?: return@withContext 0L
            try {
                val streams = services.mediaRepository.openThumbnailStreams(fileId, 0).getOrNull() ?: return@withContext 0L
                streams.use {
                    val url = it.originalUrl ?: return@withContext 0L
                    val opened = grabber.open(url)
                    val duration = if (opened) grabber.durationMs else 0L
                    PikoLog.i(TAG, "列表与详情里都没有时长，打开原画读：${if (duration > 0) "读到了" else "读不到（opened=$opened）"}")
                    duration
                }
            } finally {
                grabber.close()
            }
        }
    }

    /**
     * 一集开头、结尾的声音指纹。转码流按字节读那两段（几十 MB），没有转码流时让解码器在原画上按索引跳过去读。
     */
    private suspend fun episodeAudio(fileId: String, durationMs: Long): EpisodeMatcher.Episode? {
        val decoder = newAudioDecoder?.invoke() ?: return null
        val sound = EpisodeSound(decoder, tempDirectory)
        val streams = services.mediaRepository.openThumbnailStreams(fileId, durationMs).getOrNull() ?: return null
        return streams.use {
            suspend fun print(span: MediaMarks.Span) = attempt {
                it.transcode?.let { transcode -> sound.fromTs(transcode.reader, durationMs, span.startMs, span.lengthMs) }
            } ?: it.originalUrl?.let { url -> attempt { sound.fromLocation(url, span.startMs, span.lengthMs) } }
            val head = print(EpisodeSound.headSpan(durationMs))
            val tail = print(EpisodeSound.tailSpan(durationMs))
            PikoLog.i(TAG, "片头片尾的声音：开头${if (head != null) "有" else "无"}、结尾${if (tail != null) "有" else "无"}，读了 ${sound.networkBytes / (1024 * 1024)} MB")
            EpisodeMatcher.Episode(fileId, head, tail)
        }
    }

    /** 做 [block]，出错（取消除外）当作没有。 */
    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PikoLog.w(TAG, "取声音失败：${e::class.simpleName}")
        null
    }

    /** [gcid] 的分段：这次运行里记着的，没有再去网盘取。都没有为 null。 */
    private suspend fun loadMarks(gcid: String): MediaMarks? {
        marksLock.withLock { knownMarks[gcid] }?.let { return it }
        val loaded = attempt { cloud.loadMarks(gcid) } ?: return null
        return marksLock.withLock { knownMarks.getOrPut(gcid) { loaded } }
    }

    /** 记下并传到网盘（在后台，失败只记日志，界面上的改动照样生效）。认不出的视频（没有 gcid）只记不传。 */
    fun saveMarks(marks: MediaMarks) {
        scope.launch {
            marksLock.withLock { knownMarks[marks.gcid] = marks }
            if (marks.gcid.isBlank()) return@launch
            try {
                cloud.saveMarks(marks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PikoLog.w(TAG, "分段没存到网盘：${e::class.simpleName}")
            }
        }
    }

    /**
     * 播放页用：读出这个视频的分段交给 [onMarks]（没有时交一份空的，好让人手动加）。
     * 还没做过场景分点、设置里开着、而这次的预览做齐了，就在后台做一次：用预览帧粗分，
     * 再另开一路画面来源取几十帧细化（只在主播放器闲着时取），做好存到网盘。调用方取消即停。
     */
    suspend fun followMarks(
        fileId: String,
        info: PlayableMediaInfo?,
        localPath: String?,
        durationMs: Long,
        session: ThumbnailEngine.Session,
        busy: StateFlow<Boolean>,
        onMarks: (MediaMarks) -> Unit,
    ) {
        val roundedDuration = durationMs / 1000 * 1000
        if (roundedDuration <= 0) return
        val gcid = info?.gcid.orEmpty().uppercase()
        val marks = (if (gcid.isNotBlank()) loadMarks(gcid) else null) ?: MediaMarks(gcid = gcid, durationMs = roundedDuration)
        onMarks(marks)
        if (marks.scenesDone || !settings.sceneMarks.value) return
        val finished = session.progress.first { it.state == ThumbnailState.Complete || it.state == ThumbnailState.Unavailable }
        if (finished.state != ThumbnailState.Complete) return
        val frames = session.frames()
        if (frames.size < MIN_SCENE_FRAMES) return
        val sources = attempt { openSources(fileId, localPath, roundedDuration) }.orEmpty()
        val scenes = try {
            val source = sources.firstOrNull()
            SceneAnalysis.analyze(frames, session.plan.durationMs, fetch = source?.let { s ->
                { time ->
                    // 主播放器要带宽时等它
                    busy.first { !it }
                    s.frameNear(time)
                }
            })
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
        PikoLog.i(TAG, "看视频时做了场景分点：${scenes.size} 个")
        // 做的这会儿用户可能已经手动改过：以最新的为准，手动改过的不覆盖
        val latest = marksLock.withLock { knownMarks[gcid] } ?: marks
        if (latest.scenesEdited || latest.scenesDone) {
            onMarks(latest)
            return
        }
        val done = latest.copy(scenes = scenes, scenesDone = true)
        saveMarks(done)
        onMarks(done)
    }

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
            override val canFindEpisodes = newAudioDecoder != null

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

        // 预览帧少于这么多不做场景分点：太稀了分不准
        const val MIN_SCENE_FRAMES = 8
    }
}
