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

/** 一集放完、点下一集时往哪走。只在同一个分区里走（正片放完不跳进 PV），见 PlayerScreenState。 */
enum class PlayOrder(val label: String) {
    Sequential("顺序播放"),
    Shuffle("随机播放"),
    LoopList("列表循环"),
    RepeatOne("单集循环"),
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

    /** 看视频时自动做场景分点（预览做齐之后，再取几十帧细化）。 */
    private val _sceneMarks = MutableStateFlow(read("player.sceneMarks", "true").toBoolean())
    val sceneMarks: StateFlow<Boolean> = _sceneMarks.asStateFlow()

    /** 进到认出的片头、片尾时自动跳过。默认关：先给按钮，由人决定跳不跳。 */
    private val _autoSkipIntro = MutableStateFlow(read("player.autoSkipIntro", "false").toBoolean())
    val autoSkipIntro: StateFlow<Boolean> = _autoSkipIntro.asStateFlow()

    /** 一集放完自动接着放。关了就停在片尾。 */
    private val _continuousPlay = MutableStateFlow(read("player.continuous", "true").toBoolean())
    val continuousPlay: StateFlow<Boolean> = _continuousPlay.asStateFlow()

    private val _playOrder = MutableStateFlow(enumOf("player.order", PlayOrder.Sequential))
    val playOrder: StateFlow<PlayOrder> = _playOrder.asStateFlow()

    /** 文件列表的默认排序（PikoFileSortOrder 的名字）。没单独记过排序的文件夹、关了记忆时所有文件夹，都按它排。 */
    private val _defaultSort = MutableStateFlow(read("sort.default", "TIME_DESC"))
    val defaultSort: StateFlow<String> = _defaultSort.asStateFlow()

    /** 在某个文件夹里改了排序，记住它，下次进来还是这样排。 */
    private val _rememberSort = MutableStateFlow(read("sort.remember", "true").toBoolean())
    val rememberSort: StateFlow<Boolean> = _rememberSort.asStateFlow()

    // 各文件夹记住的排序：文件夹 ID → 排序名。最近改的排在后面，超过上限先丢最久没改的
    private val folderSorts = LinkedHashMap(decodeFolderSorts(read("sort.folders", "")))

    /** 默认排序改了、记忆开关拨了时加一。开着的文件列表看它把眼前的文件夹重新排。 */
    private val _sortVersion = MutableStateFlow(0)
    val sortVersion: StateFlow<Int> = _sortVersion.asStateFlow()

    /** 这个文件夹该怎么排：记住过的（记忆开着时），否则默认排序。根目录的 ID 是空串。 */
    fun sortFor(folderId: String): String {
        val remembered = if (_rememberSort.value) synchronized(lock) { folderSorts[folderKey(folderId)] } else null
        return remembered ?: _defaultSort.value
    }

    /** 用户在这个文件夹里改了排序。记忆关着时不记，只管这一眼。 */
    fun setFolderSort(folderId: String, order: String) {
        if (!_rememberSort.value) return
        val encoded = synchronized(lock) {
            val key = folderKey(folderId)
            folderSorts.remove(key)
            // 改成和默认一样就不必记：以后改了默认，它跟着走
            if (order != _defaultSort.value) folderSorts[key] = order
            while (folderSorts.size > MAX_FOLDER_SORTS) folderSorts.remove(folderSorts.keys.first())
            encodeFolderSorts(folderSorts)
        }
        write("sort.folders", encoded) {}
    }

    /** 设默认排序：同时清掉各文件夹记住的，全部按新的默认排，之后单个文件夹再改的再记。 */
    fun setDefaultSort(order: String) {
        synchronized(lock) { folderSorts.clear() }
        write("sort.folders", "") {}
        write("sort.default", order) {
            _defaultSort.value = order
            _sortVersion.value++
        }
    }

    fun setRememberSort(enabled: Boolean) = write("sort.remember", enabled.toString()) {
        _rememberSort.value = enabled
        _sortVersion.value++
    }

    fun setContinuousPlay(enabled: Boolean) = write("player.continuous", enabled.toString()) { _continuousPlay.value = enabled }

    fun setPlayOrder(order: PlayOrder) = write("player.order", order.name) { _playOrder.value = order }

    fun setSceneMarks(enabled: Boolean) = write("player.sceneMarks", enabled.toString()) { _sceneMarks.value = enabled }

    fun setAutoSkipIntro(enabled: Boolean) = write("player.autoSkipIntro", enabled.toString()) { _autoSkipIntro.value = enabled }

    fun setThumbnailsEnabled(enabled: Boolean) = write("thumbnail.enabled", enabled.toString()) { _thumbnailsEnabled.value = enabled }

    fun setThumbnailDensity(density: ThumbnailDensity) = write("thumbnail.density", density.name) { _thumbnailDensity.value = density }

    fun setThumbnailCacheLimit(bytes: Long) = write("thumbnail.cacheLimitBytes", bytes.toString()) { _thumbnailCacheLimit.value = bytes }

    fun setDragSeekMode(mode: DragSeekMode) = write("player.dragSeek", mode.name) { _dragSeekMode.value = mode }

    fun setPerformanceOverlay(shown: Boolean) = write("player.performanceOverlay", shown.toString()) { _performanceOverlay.value = shown }

    fun setPrefetchNext(enabled: Boolean) = write("player.prefetchNext", enabled.toString()) { _prefetchNext.value = enabled }

    private fun read(key: String, default: String): String = values[key] ?: default

    private fun folderKey(folderId: String) = folderId.ifEmpty { ROOT_KEY }

    // 存成一行「ID=排序|ID=排序」：网盘的文件夹 ID 与库的 ID（piko:starred）里都没有 = 与 |
    private fun encodeFolderSorts(map: Map<String, String>): String = map.entries.joinToString("|") { "${it.key}=${it.value}" }

    private fun decodeFolderSorts(text: String): Map<String, String> =
        text.split('|').mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] } }.toMap()

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

        /** 最多记住这么多个文件夹的排序，一行几十 KB。 */
        const val MAX_FOLDER_SORTS = 2000

        private const val ROOT_KEY = "~root"
    }
}
