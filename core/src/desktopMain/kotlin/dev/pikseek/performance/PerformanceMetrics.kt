package dev.pikseek.performance

import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放与缩略图的计时，只在本机内存里，给性能浮层与基准导出用，不上传。
 *
 * 各处只管报数（[update]、[sample]），不关心谁在看。浮层读 [state]；[exportText] 把最近的样本
 * 写成文本，拿来和 Piko 的同一批视频对比，不靠「感觉更快」。
 */
object PerformanceMetrics {
    data class Snapshot(
        /** 取文件详情（含各清晰度与直链）的一次 API 往返。 */
        val mediaApiMillis: Long? = null,
        /** 详情到手后建好本机代理会话、交出播放地址。 */
        val mediaUrlMillis: Long? = null,
        /** 从点开到把地址交给 mpv。 */
        val mpvLoadMillis: Long? = null,
        /** 从点开到第一帧。 */
        val firstFrameMillis: Long? = null,
        /** 最近一次拖动到画面重新走起来。 */
        val seekMillis: Long? = null,
        /** 最近一次换集到第一帧。 */
        val switchMillis: Long? = null,
        val cdnHost: String? = null,
        val droppedFrames: Long? = null,
        val thumbnailCoarseDone: Int = 0,
        val thumbnailCoarseTotal: Int = 0,
        val thumbnailFullDone: Int = 0,
        val thumbnailFullTotal: Int = 0,
        /** 当前视频的预览缓存在磁盘上占的字节。 */
        val previewCacheBytes: Long = 0,
        /** 缩略图引擎为当前视频从网上取的字节。 */
        val thumbnailNetworkBytes: Long = 0,
        val thumbnailState: String = "",
        /** 下一条已备好描述（详情与直链），点下一条时不必再查。 */
        val nextPrefetched: Boolean = false,
    )

    class Sample(val name: String, val millis: Long, val atMillis: Long, val note: String)

    private val current = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = current.asStateFlow()

    private val samples = ConcurrentLinkedDeque<Sample>()
    private const val MAX_SAMPLES = 400

    fun update(change: (Snapshot) -> Snapshot) {
        current.update(change)
    }

    /** 记一个样本，[note] 不能带文件名：导出的文本可能发给别人。 */
    fun sample(name: String, millis: Long, note: String = "") {
        samples.addLast(Sample(name, millis, System.currentTimeMillis(), note))
        while (samples.size > MAX_SAMPLES) samples.pollFirst()
    }

    fun samples(): List<Sample> = samples.toList()

    /** 换了视频：与上一个视频有关的读数清掉，样本留着。 */
    fun resetForNewMedia() {
        current.update { Snapshot(switchMillis = it.switchMillis) }
    }

    /** 每个指标的次数、中位数、最小与最大，再附逐条样本。 */
    fun exportText(): String = buildString {
        appendLine("PikSeek performance samples (local only)")
        appendLine()
        val all = samples()
        if (all.isEmpty()) {
            appendLine("（还没有样本：播放一个视频、拖动几次进度条后再导出）")
            return@buildString
        }
        appendLine("| metric | n | median ms | min ms | max ms |")
        appendLine("| --- | --- | --- | --- | --- |")
        all.groupBy { it.name }.toSortedMap().forEach { (name, group) ->
            val sorted = group.map { it.millis }.sorted()
            appendLine("| $name | ${sorted.size} | ${median(sorted)} | ${sorted.first()} | ${sorted.last()} |")
        }
        appendLine()
        all.forEach { appendLine("${it.atMillis}\t${it.name}\t${it.millis} ms\t${it.note}") }
    }

    private fun median(sorted: List<Long>): Long =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}
