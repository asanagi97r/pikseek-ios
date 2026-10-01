package dev.pikseek.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 时间轴预览的密度。张数随片长分档，见缩略图模块的 ThumbnailPlan。 */
enum class ThumbnailDensity(val label: String) {
    Low("低"),
    Medium("中"),
    High("高"),
}

/** 拖动进度条时主画面跟不跟。 */
enum class DragSeekMode(val label: String, val description: String) {
    Off("关", "拖动时只看预览图，松手才跳转"),
    Adaptive("自适应", "慢慢拖时画面跟着走，快速扫过时只看预览图"),
    Always("始终", "拖动时画面一直跟着走，网络慢时会卡"),
}

/** [AppSettings] 的落盘处：一组「键 = 值」。桌面是数据目录下的 properties 文件，iOS 是程序自己的偏好。 */
interface SettingsStore {
    /** 读不出来时返回空表，不抛异常。 */
    fun load(): Map<String, String>

    /** 存不下时静默放弃：设置在内存里照常生效，只是下次启动回到旧值。 */
    fun save(values: Map<String, String>)
}

/**
 * PikSeek 自己新增的几项设置：时间轴预览、拖动方式、性能浮层。每台设备各自的，
 * 不参与 Piko 的设置同步（那份会写进网盘）。
 *
 * 不含任何机密。
 */
class AppSettings(private val store: SettingsStore) {
    private val lock = SynchronizedObject()
    private val values = HashMap(store.load())

    private val _thumbnailsEnabled = MutableStateFlow(read("thumbnail.enabled", "true").toBoolean())
    val thumbnailsEnabled: StateFlow<Boolean> = _thumbnailsEnabled.asStateFlow()

    private val _thumbnailDensity = MutableStateFlow(enumOf("thumbnail.density", ThumbnailDensity.Medium))
    val thumbnailDensity: StateFlow<ThumbnailDensity> = _thumbnailDensity.asStateFlow()

    /** 预览缓存的磁盘上限，字节。0 表示不限。 */
    private val _thumbnailCacheLimit = MutableStateFlow(read("thumbnail.cacheLimitBytes", DEFAULT_CACHE_LIMIT.toString()).toLongOrNull() ?: DEFAULT_CACHE_LIMIT)
    val thumbnailCacheLimit: StateFlow<Long> = _thumbnailCacheLimit.asStateFlow()

    private val _dragSeekMode = MutableStateFlow(enumOf("player.dragSeek", DragSeekMode.Adaptive))
    val dragSeekMode: StateFlow<DragSeekMode> = _dragSeekMode.asStateFlow()

    private val _performanceOverlay = MutableStateFlow(read("player.performanceOverlay", "false").toBoolean())
    val performanceOverlay: StateFlow<Boolean> = _performanceOverlay.asStateFlow()

    /** 播放时提前备好下一条的描述（详情与直链），点下一条时省掉一次查询。 */
    private val _prefetchNext = MutableStateFlow(read("player.prefetchNext", "true").toBoolean())
    val prefetchNext: StateFlow<Boolean> = _prefetchNext.asStateFlow()

    fun setThumbnailsEnabled(enabled: Boolean) = write("thumbnail.enabled", enabled.toString()) { _thumbnailsEnabled.value = enabled }

    fun setThumbnailDensity(density: ThumbnailDensity) = write("thumbnail.density", density.name) { _thumbnailDensity.value = density }

    fun setThumbnailCacheLimit(bytes: Long) = write("thumbnail.cacheLimitBytes", bytes.toString()) { _thumbnailCacheLimit.value = bytes }

    fun setDragSeekMode(mode: DragSeekMode) = write("player.dragSeek", mode.name) { _dragSeekMode.value = mode }

    fun setPerformanceOverlay(shown: Boolean) = write("player.performanceOverlay", shown.toString()) { _performanceOverlay.value = shown }

    fun setPrefetchNext(enabled: Boolean) = write("player.prefetchNext", enabled.toString()) { _prefetchNext.value = enabled }

    private fun read(key: String, default: String): String = values[key] ?: default

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == values[key] } ?: default

    private fun write(key: String, value: String, apply: () -> Unit) = synchronized(lock) {
        values[key] = value
        apply()
        runCatching { store.save(HashMap(values)) }
        Unit
    }

    companion object {
        const val GIB = 1024L * 1024L * 1024L
        const val DEFAULT_CACHE_LIMIT = 2 * GIB

        /** 设置页给的几档上限，0 是不限。 */
        val CACHE_LIMIT_CHOICES: List<Long> = listOf(2 * GIB, 5 * GIB, 10 * GIB, 0L)
    }
}
