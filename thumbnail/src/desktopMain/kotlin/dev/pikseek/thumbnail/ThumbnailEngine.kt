package dev.pikseek.thumbnail

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 引擎眼下在做什么。 */
enum class ThumbnailState {
    /** 在读本机缓存。 */
    LoadingCache,

    /** 在生成。 */
    Generating,

    /** 主播放器正忙（缓冲、拖动、掉帧），让路中。 */
    Yielding,

    /** 全部就绪。 */
    Complete,

    /** 这个视频做不出预览（来源都用不了）。 */
    Unavailable,
}

data class ThumbnailProgress(
    val state: ThumbnailState = ThumbnailState.LoadingCache,
    val coarseDone: Int = 0,
    val coarseTotal: Int = 0,
    val fullDone: Int = 0,
    val fullTotal: Int = 0,
    /** 眼下用的画面来源。 */
    val source: String = "",
    /** 为取帧从网络读的字节。来源自己读网络、数不出来时为 -1。 */
    val networkBytes: Long = 0,
    /** 这个视频的预览在磁盘上占的字节。 */
    val cacheBytes: Long = 0,
    /** 这一次打开时有多少帧直接来自本机缓存。 */
    val fromCache: Int = 0,
    /** 每多一帧就加一：界面据此重读悬停处的图。 */
    val revision: Int = 0,
) {
    val fraction: Float get() = if (fullTotal == 0) 0f else fullDone.toFloat() / fullTotal
}

/**
 * 时间轴缩略图引擎。与主播放器完全分开：自己取数据、自己解码、自己存盘，主播放器从不为它定位。
 *
 * 一个视频开一个 [Session]：先读本机缓存，缺的再生成；生成的先后见 [ThumbnailPlan.order]。
 * 悬停时界面调 [Session.frameAt]，只读内存里已有的帧，永远不等、不发请求。
 */
class ThumbnailEngine(val cache: ThumbnailCache) {
    /**
     * 为一个视频开始准备预览。调用方要等主播放器出了第一帧再调：预览不该拖慢起播。
     *
     * @param sources 画面来源，按优先顺序。前一个整个用不了才换下一个。只在缓存不全时才会被调用。
     * @param position 眼下播放到哪，毫秒。决定先做哪几格。
     * @param busy 主播放器正忙时为 true，引擎让路，不再发起新的取帧。
     * @param parallelism 允许同时取几帧，1 或 2。
     */
    fun open(
        fingerprint: MediaFingerprint,
        density: PreviewDensity,
        sources: suspend () -> List<ThumbnailSource>,
        position: () -> Long,
        busy: StateFlow<Boolean>,
        parallelism: StateFlow<Int>,
    ): Session = Session(fingerprint, ThumbnailPlan.of(fingerprint.durationMs, density), sources, position, busy, parallelism)

    inner class Session internal constructor(
        val fingerprint: MediaFingerprint,
        val plan: ThumbnailPlan,
        private val sources: suspend () -> List<ThumbnailSource>,
        private val position: () -> Long,
        private val busy: StateFlow<Boolean>,
        private val parallelism: StateFlow<Int>,
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val frames = ConcurrentHashMap<Int, ThumbnailFrame>()

        // 按真实时刻排的同一批帧，悬停时在里面找最近的
        private val byTime = ConcurrentSkipListMap<Long, ThumbnailFrame>()
        private val coarse = plan.coarseSlots.toSet()

        // 离得太远的图是误导：最多容许一个粗略间隔
        private val maxDistanceMs = (plan.durationMs / coarse.size.coerceAtLeast(1)).coerceAtLeast(2 * plan.intervalMs)
        private val _progress = MutableStateFlow(ThumbnailProgress(coarseTotal = coarse.size, fullTotal = plan.slotCount))
        val progress: StateFlow<ThumbnailProgress> = _progress.asStateFlow()

        private val queueLock = Any()
        private var queue = ArrayDeque<Int>()
        private val inFlight = HashSet<Int>()
        private val failed = HashSet<Int>()

        // 添了新帧、还没写盘的雪碧图
        private val dirtySheets = HashSet<Int>()

        // 眼下这个来源连着失败了几次、一共做出了几帧
        private val misses = AtomicInteger()
        private val produced = AtomicInteger()

        @Volatile
        private var active: ThumbnailSource? = null

        @Volatile
        private var opened: List<ThumbnailSource> = emptyList()
        private val job: Job = scope.launch { run() }

        /**
         * [timeMs] 处该显示的图：已有的帧里时刻最近的一张。附近还没有图时返回 null，界面只显示时间。
         * 只查内存，立刻返回。
         */
        fun frameAt(timeMs: Long): ThumbnailFrame? {
            val below = byTime.floorEntry(timeMs)
            val above = byTime.ceilingEntry(timeMs)
            val nearest = when {
                below == null -> above
                above == null -> below
                timeMs - below.key <= above.key - timeMs -> below
                else -> above
            } ?: return null
            return nearest.value.takeIf { abs(nearest.key - timeMs) <= maxDistanceMs }
        }

        /** 用户在 [timeMs] 附近悬停或拖动，而那里还没有图：把那几格提到最前。已有图时不动。 */
        fun prefer(timeMs: Long) {
            val wanted = plan.around(timeMs).filter { !frames.containsKey(it) }
            if (wanted.isEmpty()) return
            synchronized(queueLock) {
                wanted.asReversed().forEach { slot ->
                    if (slot !in inFlight && queue.remove(slot)) queue.addFirst(slot)
                }
            }
        }

        /** 停下并存盘。之后 [frameAt] 仍可用，只是不再有新帧。 */
        override fun close() {
            scope.launch {
                // 收尾不能被打断：存盘做到一半停下，磁盘上会是半张图
                withContext(NonCancellable) {
                    job.cancel()
                    job.join()
                    flush()
                    opened.forEach { runCatching { it.close() } }
                }
                scope.cancel()
            }
        }

        /** 等到做完或做不下去。测试用。 */
        suspend fun join() = job.join()

        private suspend fun run() = coroutineScope {
            val cached = withContext(Dispatchers.IO) { runCatching { cache.load(fingerprint, plan) }.getOrNull() }.orEmpty()
            cached.forEach { (slot, frame) -> put(slot, frame, dirty = false) }
            _progress.update { it.copy(fromCache = cached.size, cacheBytes = cache.sizeOf(fingerprint)) }
            if (frames.size >= plan.slotCount) {
                _progress.update { it.copy(state = ThumbnailState.Complete, source = "本机缓存") }
                return@coroutineScope
            }

            opened = try {
                sources()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val saver = launch { saveLoop() }
            try {
                for (source in opened) {
                    active = source
                    synchronized(queueLock) {
                        queue = ArrayDeque(plan.order(position()).filter { !frames.containsKey(it) })
                        failed.clear()
                    }
                    _progress.update { it.copy(state = ThumbnailState.Generating, source = source.description) }
                    // 这个来源整个用不了时换下一个，没做成的那几格由下一个来源重做
                    if (generateWith(source)) break
                }
            } finally {
                saver.cancel()
                withContext(NonCancellable) { flush() }
            }
            // 有几格怎么也取不到（片尾没有关键帧之类）时，有多少算多少，悬停时取最近的
            _progress.update { it.copy(state = if (frames.isEmpty()) ThumbnailState.Unavailable else ThumbnailState.Complete) }
        }

        /** 用 [source] 把队列做完。返回 false 表示这个来源整个用不了。 */
        private suspend fun generateWith(source: ThumbnailSource): Boolean {
            val unusable = AtomicBoolean(false)
            misses.set(0)
            produced.set(0)
            repeat(PASSES) { pass ->
                if (pass > 0) {
                    // 上一轮没取到的（多半是网络抖了一下）再试一轮
                    val again = synchronized(queueLock) { failed.toList().also { failed.clear() } }
                    if (again.isEmpty()) return@repeat
                    synchronized(queueLock) { queue = ArrayDeque(again) }
                }
                coroutineScope {
                    (0 until source.maxParallel.coerceIn(1, MAX_WORKERS)).map { worker ->
                        launch { work(source, worker, unusable) }
                    }.joinAll()
                }
                if (unusable.get()) return false
                // 一轮下来这个来源一帧都没做出来：当作它不行
                if (produced.get() == 0) return false
            }
            return true
        }

        private suspend fun work(source: ThumbnailSource, worker: Int, unusable: AtomicBoolean) {
            while (!unusable.get()) {
                // 第二路只在主播放器有余量时才干活
                if (worker > 0 && parallelism.value <= worker) {
                    if (synchronized(queueLock) { queue.isEmpty() && inFlight.isEmpty() }) return
                    delay(IDLE_POLL_MS)
                    continue
                }
                waitUntilIdle()
                val slot = synchronized(queueLock) { queue.removeFirstOrNull()?.also { inFlight += it } } ?: return
                try {
                    val frame = fetch(source, slot)
                    if (frame != null) {
                        put(slot, frame, dirty = true)
                        produced.incrementAndGet()
                        misses.set(0)
                    } else {
                        synchronized(queueLock) { failed += slot }
                        // 这个来源从头到现在一帧都没取出来，又连着失败了好几次：不必把整条队列都试一遍，换下一个来源
                        if (misses.incrementAndGet() >= EARLY_GIVE_UP && produced.get() == 0) {
                            unusable.set(true)
                            return
                        }
                    }
                } catch (e: SourceUnusableException) {
                    unusable.set(true)
                    return
                } finally {
                    synchronized(queueLock) { inFlight -= slot }
                }
                _progress.update { it.copy(networkBytes = source.networkBytes) }
            }
        }

        /**
         * 取一格的帧。明显全黑时往后挪一点重取：先按 0.5、1、2 秒挪；取帧只能落在关键帧上，
         * 挪这么点常常还是同一帧，所以再挪 4、8 秒。换到过三张不同的帧仍是黑的，就接受最初那张（真有黑场）。
         */
        private suspend fun fetch(source: ThumbnailSource, slot: Int): ThumbnailFrame? {
            val target = plan.slotTimeMs(slot)
            val first = attempt(source, target) ?: return null
            if (!first.isBlack()) return first
            val seen = hashSetOf(first.timeMs)
            for (shift in BLACK_RETRY_SHIFTS_MS) {
                if (seen.size > BLACK_RETRY_FRAMES) break
                val time = maxOf(target, first.timeMs) + shift
                if (time >= plan.durationMs) break
                val retry = attempt(source, time) ?: continue
                if (!seen.add(retry.timeMs)) continue
                if (!retry.isBlack()) return retry
            }
            return first
        }

        private suspend fun attempt(source: ThumbnailSource, timeMs: Long): ThumbnailFrame? = try {
            source.frameNear(timeMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceUnusableException) {
            throw e
        } catch (e: Exception) {
            // 一次读取失败（网络抖动、解码失败）：这一格先放过
            null
        }

        private fun put(slot: Int, frame: ThumbnailFrame, dirty: Boolean) {
            // 相邻两格可能取到同一个关键帧：共用一份像素
            val shared = byTime[frame.timeMs]?.takeIf { it.width == frame.width && it.height == frame.height } ?: frame
            frames[slot] = shared
            byTime[shared.timeMs] = shared
            if (dirty) synchronized(dirtySheets) { dirtySheets += slot / ThumbnailCache.FRAMES_PER_SHEET }
            _progress.update {
                it.copy(
                    coarseDone = coarse.count { coarseSlot -> frames.containsKey(coarseSlot) },
                    fullDone = frames.size,
                    revision = it.revision + 1,
                )
            }
        }

        /** 主播放器忙的时候等着；它闲下来之后再等一小会儿，免得它刚缓过来就又被抢带宽。 */
        private suspend fun waitUntilIdle() {
            if (!busy.value) return
            _progress.update { it.copy(state = ThumbnailState.Yielding) }
            while (true) {
                busy.first { !it }
                delay(RESUME_SETTLE_MS)
                if (!busy.value) break
            }
            _progress.update { it.copy(state = ThumbnailState.Generating) }
        }

        private suspend fun saveLoop() {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                flush()
            }
        }

        private suspend fun flush() {
            val sheets = synchronized(dirtySheets) { dirtySheets.toSet().also { dirtySheets.clear() } }
            if (sheets.isEmpty()) return
            withContext(Dispatchers.IO) {
                runCatching { cache.save(fingerprint, plan, active?.description.orEmpty(), HashMap(frames), sheets) }
                    // 写不进去（盘满、目录被删）：下次再试，不影响内存里的帧
                    .onFailure { synchronized(dirtySheets) { dirtySheets += sheets } }
            }
            _progress.update { it.copy(cacheBytes = cache.sizeOf(fingerprint)) }
        }
    }

    private companion object {
        const val MAX_WORKERS = 2
        const val PASSES = 2
        const val SAVE_INTERVAL_MS = 3_000L
        const val RESUME_SETTLE_MS = 800L
        const val IDLE_POLL_MS = 500L
        const val BLACK_RETRY_FRAMES = 3
        const val EARLY_GIVE_UP = 6
        val BLACK_RETRY_SHIFTS_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
    }
}
