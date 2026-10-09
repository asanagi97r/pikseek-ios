package dev.pikseek.thumbnail

import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个视频的进度条分段：场景分点、片头、片尾。按 gcid 认，存到网盘上与预览包同一个文件夹（名字见 [fileName]），
 * 几百字节的 JSON。与预览包分开存：重做预览不会冲掉手改过的分点，改一个分点也不必重传几百 KB 的图。
 *
 * 自动做的与手改的分开记：手改过的（[scenesEdited]、[episodeEdited]）自动分析不再覆盖。
 */
@Serializable
data class MediaMarks(
    val version: Int = VERSION,
    val gcid: String,
    val durationMs: Long,
    /** 场景分点：除第一段外每一段的开头，毫秒，升序。 */
    val scenes: List<Long> = emptyList(),
    /** 场景分点做过了。自动分析一个点都没找到也算做过，下次不再白做。 */
    val scenesDone: Boolean = false,
    /** 用户手动改过场景分点。 */
    val scenesEdited: Boolean = false,
    val intro: Span? = null,
    val outro: Span? = null,
    /** 片头片尾认过了，没认出来也算。 */
    val episodeDone: Boolean = false,
    /** 用户手动定过片头或片尾。 */
    val episodeEdited: Boolean = false,
) {
    /** 一段时间，毫秒，[startMs] 含、[endMs] 不含。 */
    @Serializable
    data class Span(val startMs: Long, val endMs: Long) {
        operator fun contains(timeMs: Long): Boolean = timeMs in startMs until endMs

        val lengthMs: Long get() = endMs - startMs
    }

    val fileName: String get() = fileNameOf(gcid)

    /** 进度条上该画刻度、拖动时该吸住的全部时刻：场景分点，以及片头片尾的两端（片头的开头是 0 时不算）。 */
    val snapPoints: List<Long>
        get() = buildList {
            addAll(scenes)
            intro?.let { if (it.startMs > 0) add(it.startMs); add(it.endMs) }
            outro?.let { add(it.startMs); if (it.endMs < durationMs) add(it.endMs) }
        }.filter { it in 1 until durationMs }.distinct().sorted()

    /** [timeMs] 在第几段（从 0 起）。 */
    fun segmentAt(timeMs: Long): Int = scenes.count { it <= timeMs }

    val segmentCount: Int get() = scenes.size + 1

    /** 离 [timeMs] 最近、相差不超过 [toleranceMs] 的分点，没有为 null。 */
    fun nearestPoint(timeMs: Long, toleranceMs: Long): Long? =
        snapPoints.minByOrNull { abs(it - timeMs) }?.takeIf { abs(it - timeMs) <= toleranceMs }

    /** [timeMs] 之后的下一个分点。 */
    fun nextPoint(timeMs: Long): Long? = snapPoints.firstOrNull { it > timeMs + STEP_SLACK_MS }

    /** [timeMs] 之前的上一个分点。正在一段的开头附近时再往前一个，连按才退得动。 */
    fun previousPoint(timeMs: Long): Long? = snapPoints.lastOrNull { it < timeMs - STEP_SLACK_MS }

    /** 加一个场景分点（手动）。与已有的离得太近时不加。 */
    fun withScene(timeMs: Long): MediaMarks {
        if (timeMs <= MIN_GAP_MS || timeMs >= durationMs - MIN_GAP_MS) return this
        if (scenes.any { abs(it - timeMs) < MIN_GAP_MS }) return this
        return copy(scenes = (scenes + timeMs).sorted(), scenesDone = true, scenesEdited = true)
    }

    /** 删掉离 [timeMs] 最近、相差不超过 [toleranceMs] 的场景分点（手动）。 */
    fun withoutScene(timeMs: Long, toleranceMs: Long): MediaMarks {
        val target = scenes.minByOrNull { abs(it - timeMs) }?.takeIf { abs(it - timeMs) <= toleranceMs } ?: return this
        return copy(scenes = scenes - target, scenesDone = true, scenesEdited = true)
    }

    /** 片头到 [timeMs] 结束（手动）。原来认出的片头开头留着，没有就从 0 起。 */
    fun withIntroEnd(timeMs: Long): MediaMarks {
        val start = intro?.startMs?.takeIf { it < timeMs } ?: 0L
        if (timeMs - start < MIN_GAP_MS) return this
        return copy(intro = Span(start, timeMs), episodeDone = true, episodeEdited = true)
    }

    /** 片尾从 [timeMs] 开始（手动）。原来认出的片尾结尾留着，没有就到片末。 */
    fun withOutroStart(timeMs: Long): MediaMarks {
        val end = outro?.endMs?.takeIf { it > timeMs } ?: durationMs
        if (end - timeMs < MIN_GAP_MS) return this
        return copy(outro = Span(timeMs, end), episodeDone = true, episodeEdited = true)
    }

    fun withoutIntroOutro(): MediaMarks = copy(intro = null, outro = null, episodeDone = true, episodeEdited = true)

    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val VERSION = 1
        const val EXTENSION = ".psmarks"

        /** 两个分点至少隔这么远；手动加点时离片头片尾也至少这么远。 */
        const val MIN_GAP_MS = 3_000L

        // 上一个、下一个分点：落在分点后面这么近之内也算在分点上
        private const val STEP_SLACK_MS = 1_500L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fileNameOf(gcid: String): String = "${gcid.uppercase()}$EXTENSION"

        /** 文件名里的 gcid，不是这种名字时为 null。 */
        fun gcidOf(fileName: String): String? {
            if (!fileName.endsWith(EXTENSION)) return null
            val gcid = withoutCopyNumber(fileName.removeSuffix(EXTENSION))
            if (gcid.length != 40 || !gcid.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
            return gcid.uppercase()
        }

        /** 读不懂、版本更新、或数值不合理时为 null。分点顺手排好、去掉越界的。 */
        fun decode(bytes: ByteArray): MediaMarks? {
            val marks = runCatching { json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
            if (marks.version > VERSION || marks.durationMs <= 0) return null
            fun Span?.valid(): Span? = this?.takeIf { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= marks.durationMs + MIN_GAP_MS }
            return marks.copy(
                gcid = marks.gcid.uppercase(),
                scenes = marks.scenes.filter { it in 1 until marks.durationMs }.distinct().sorted(),
                intro = marks.intro.valid(),
                outro = marks.outro.valid(),
            )
        }
    }
}
