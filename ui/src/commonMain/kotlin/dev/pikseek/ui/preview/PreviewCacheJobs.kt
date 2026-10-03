package dev.pikseek.ui.preview

import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.isVaulted
import dev.piko.shared.log.PikoLog
import dev.pikseek.thumbnail.MediaFingerprint
import dev.pikseek.thumbnail.PreviewDensity
import dev.pikseek.thumbnail.PreviewPackName
import dev.pikseek.thumbnail.ThumbnailCache
import dev.pikseek.thumbnail.ThumbnailEngine
import dev.pikseek.thumbnail.ThumbnailPlan
import dev.pikseek.thumbnail.ThumbnailSource
import dev.pikseek.thumbnail.ThumbnailState
import io.github.nihildigit.pikpak.FileStat
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 要给哪些视频做预览缓存。 */
sealed interface PreviewCacheTarget {
    /** 一个文件夹里的视频，可以连同子文件夹。 */
    data class Folder(val id: String, val name: String) : PreviewCacheTarget

    /** 点名的几个视频。 */
    data class Files(val files: List<FileStat>) : PreviewCacheTarget
}

/**
 * 一次「预览缓存」。
 *
 * @param includeSubfolders 文件夹连同它的子文件夹（一层层往下）
 * @param overwrite 已有的也重做。为 false 时只做没有的、没做完的、档次与这次不同的
 */
class PreviewCacheRequest(
    val target: PreviewCacheTarget,
    val density: PreviewDensity,
    val includeSubfolders: Boolean,
    val overwrite: Boolean,
)

/** 预览缓存的任务眼下做到哪了。 */
data class PreviewJobsState(
    val running: Boolean = false,
    /** 眼下这一批是什么，例如「文件夹 番剧」。 */
    val label: String = "",
    /** 还在列文件夹、数视频。 */
    val scanning: Boolean = false,
    val total: Int = 0,
    val made: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 没做成的原因与个数，例如「拿不到时长」→ 5。 */
    val failures: Map<String, Int> = emptyMap(),
    /** 正在做的视频名与它做到了几成。 */
    val current: String? = null,
    val currentFraction: Float = 0f,
    /** 排在后面的批数。 */
    val queued: Int = 0,
    /** 上一批做完时的总结；正在做时为 null。 */
    val summary: String? = null,
) {
    val finished: Int get() = made + skipped + failed
}

/**
 * 给一批视频做预览、打包、传到网盘（[PreviewCloud]）。一批接一批、一个视频接一个视频地做：
 * 每个视频自己就有两路并行取帧，再多开只是跟主播放器抢带宽。
 *
 * 做的时候用 [ThumbnailEngine.CacheMode.Exact]：用户选了哪档就是哪档，本机已有的别的档次换掉。
 * 停下（[cancel]）时做了一半的留在本机，下次接着做；网盘上的只在一个视频做完后才换。
 */
class PreviewCacheJobs(
    private val scope: CoroutineScope,
    private val cloud: PreviewPackStore,
    private val cache: ThumbnailCache,
    private val engine: ThumbnailEngine,
    private val listFolder: suspend (String) -> List<FileStat>,
    private val openSources: suspend (fileId: String, durationMs: Long) -> List<ThumbnailSource>,
    /** 列表里没给时长时再去找（文件详情、打开视频读），毫秒；找不到为 0。 */
    private val probeDurationMs: suspend (fileId: String) -> Long = { 0L },
) {
    private val _state = MutableStateFlow(PreviewJobsState())
    val state: StateFlow<PreviewJobsState> = _state.asStateFlow()

    /** 正在做的视频：gcid → 做到几成。角标在做的时候照它画，做完从这里拿掉，改照网盘上的包画。 */
    private val _live = MutableStateFlow<Map<String, Float>>(emptyMap())
    val live: StateFlow<Map<String, Float>> = _live.asStateFlow()

    private val lock = SynchronizedObject()
    private val queue = ArrayDeque<PreviewCacheRequest>()
    private var worker: Job? = null

    fun enqueue(request: PreviewCacheRequest) {
        synchronized(lock) {
            queue.addLast(request)
            _state.update { it.copy(queued = queue.size, summary = null) }
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
    }

    /** 停下正在做的，排着的也不做了。 */
    fun cancel() {
        synchronized(lock) {
            queue.clear()
            worker?.cancel()
            worker = null
        }
        _live.value = emptyMap()
        _state.update { it.copy(running = false, scanning = false, current = null, queued = 0, summary = "已停止") }
    }

    private suspend fun drain() {
        while (true) {
            val request = synchronized(lock) { queue.removeFirstOrNull().also { _state.update { s -> s.copy(queued = queue.size) } } } ?: break
            runBatch(request)
        }
    }

    private suspend fun runBatch(request: PreviewCacheRequest) {
        val label = when (val target = request.target) {
            is PreviewCacheTarget.Folder -> "文件夹「${target.name}」" + if (request.includeSubfolders) "及子文件夹" else ""
            is PreviewCacheTarget.Files -> if (target.files.size == 1) target.files.single().name else "${target.files.size} 个视频"
        }
        _state.update { PreviewJobsState(running = true, label = label, scanning = true, queued = it.queued) }
        val videos = try {
            collect(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "列文件夹失败：${e::class.simpleName}")
            _state.update { it.copy(running = false, scanning = false, summary = "列文件夹失败，没有做") }
            return
        }
        _state.update { it.copy(scanning = false, total = videos.size) }
        // 先把网盘上的列表取新：判断跳过哪些要用
        cloud.refresh(force = true)
        for (video in videos) {
            _state.update { it.copy(current = video.name, currentFraction = 0f) }
            when (val outcome = process(video, request)) {
                is Outcome.Made -> _state.update { it.copy(made = it.made + 1) }
                is Outcome.Skipped -> _state.update { it.copy(skipped = it.skipped + 1) }
                is Outcome.Failed -> _state.update { it.copy(failed = it.failed + 1, failures = it.failures + (outcome.reason to (it.failures[outcome.reason] ?: 0) + 1)) }
            }
        }
        _state.update {
            it.copy(
                running = false,
                current = null,
                summary = buildString {
                    append("$label：做好 ${it.made} 个")
                    if (it.skipped > 0) append("，已有跳过 ${it.skipped} 个")
                    if (it.failed > 0) append("，没做成 ${it.failed} 个（" + it.failures.entries.joinToString("，") { (reason, count) -> "$reason $count" } + "）")
                    if (it.total == 0) append("（这里没有视频）")
                },
            )
        }
    }

    /** 这一批里的视频。同一份内容（gcid 相同）出现几次只做一次。 */
    private suspend fun collect(request: PreviewCacheRequest): List<FileStat> {
        val found = LinkedHashMap<String, FileStat>()
        fun take(file: FileStat) {
            if (file.isFolder || file.isVaulted || !file.isPlayableVideo()) return
            found.getOrPut(file.hash.uppercase().ifBlank { "id:${file.id}" }) { file }
        }
        when (val target = request.target) {
            is PreviewCacheTarget.Files -> target.files.forEach(::take)
            is PreviewCacheTarget.Folder -> {
                val pending = ArrayDeque(listOf(target.id))
                val seen = HashSet<String>()
                while (pending.isNotEmpty()) {
                    val folder = pending.removeFirst()
                    if (!seen.add(folder)) continue
                    for (file in listFolder(folder)) {
                        if (file.isFolder) {
                            // 预览缓存自己的文件夹里没有视频，也不该往里钻
                            if (request.includeSubfolders && file.name != PreviewCloud.FOLDER_NAME && !file.trashed) pending.addLast(file.id)
                        } else {
                            take(file)
                        }
                    }
                    _state.update { it.copy(total = found.size) }
                }
            }
        }
        return found.values.toList()
    }

    private sealed interface Outcome {
        data object Made : Outcome

        data object Skipped : Outcome

        class Failed(val reason: String) : Outcome
    }

    private suspend fun process(file: FileStat, request: PreviewCacheRequest): Outcome {
        val gcid = file.hash.uppercase()
        if (gcid.isBlank()) return Outcome.Failed("网盘没给内容哈希")
        val density = request.density
        // 先看要不要跳过：跳过的不必去找时长
        val existing = cloud.lookup(gcid)
        if (!request.overwrite && existing != null && existing.name.isComplete && existing.name.density == density) return Outcome.Skipped
        // 网盘文件列表里的时长，秒。有些文件（比如部分 m3u8）列表里没有，再查详情、再打开视频读。取整到秒，与看视频时的算法一致
        val listed = ((file.params["duration"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val probed = if (listed > 0) {
            listed
        } else {
            try {
                probeDurationMs(file.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0L
            }
        }
        val durationMs = probed / 1000 * 1000
        if (durationMs <= 0) {
            PikoLog.w(TAG, "拿不到时长，跳过一个视频")
            return Outcome.Failed("拿不到时长")
        }

        val fingerprint = MediaFingerprint(file.id, gcid, file.sizeBytes, durationMs)
        val slots = ThumbnailPlan.of(durationMs, density).slotCount
        val session = engine.open(
            fingerprint = fingerprint,
            density = density,
            sources = { openSources(file.id, durationMs) },
            position = { 0L },
            busy = IDLE,
            parallelism = TWO,
            mode = ThumbnailEngine.CacheMode.Exact,
            prepare = {
                withContext(Dispatchers.IO) {
                    if (request.overwrite) {
                        cache.delete(fingerprint)
                    } else if (existing != null && existing.name.density == density && cache.stored(fingerprint)?.slotCount != slots) {
                        // 上次在别处做了一半：取回来接着做
                        cache.importPack(fingerprint, cloud.download(existing))
                    }
                }
            },
        )
        val watcher = scope.launch {
            session.progress.collect { progress ->
                val fraction = progress.fraction
                _live.update { it + (gcid to fraction) }
                _state.update { it.copy(currentFraction = fraction) }
            }
        }
        try {
            session.join()
            if (session.progress.value.state == ThumbnailState.Unavailable) return Outcome.Failed("取不出画面")
            val stored = withContext(Dispatchers.IO) { cache.stored(fingerprint) } ?: return Outcome.Failed("取不出画面")
            if (stored.frameCount == 0) return Outcome.Failed("取不出画面")
            val pack = withContext(Dispatchers.IO) { cache.exportPack(fingerprint) } ?: return Outcome.Failed("打包失败")
            cloud.upload(PreviewPackName(gcid, density, stored.frameCount, stored.slotCount), pack)
            return Outcome.Made
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PikoLog.w(TAG, "一个视频的预览缓存没做成：${e::class.simpleName}")
            return Outcome.Failed("上传或网络出错")
        } finally {
            watcher.cancel()
            session.close()
            _live.update { it - gcid }
        }
    }

    private companion object {
        const val TAG = "PreviewJobs"
        val IDLE = MutableStateFlow(false)
        val TWO = MutableStateFlow(2)
    }
}
