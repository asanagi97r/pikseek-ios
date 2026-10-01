package dev.pikseek.thumbnail

import kotlin.math.abs

/** 预览密度，对应设置里的低、中、高。 */
enum class PreviewDensity { Low, Medium, High }

/**
 * 一个视频要生成哪些缩略图、按什么先后生成。纯计算，不碰网络与磁盘。
 *
 * 整条时间轴均分成 [slotCount] 格，每格取正中的时刻（[slotTimeMs]）。其中挑出 [coarseSlots] 作粗略预览，
 * 最先做完：它们均匀铺满全片，做完之后在时间轴上任何一处悬停，都至少有一张相距不远的图。
 */
class ThumbnailPlan(val durationMs: Long, val slotCount: Int) {
    init {
        require(durationMs > 0) { "时长必须为正" }
        require(slotCount > 0) { "至少一格" }
    }

    /** 相邻两格的间隔。 */
    val intervalMs: Long get() = durationMs / slotCount

    fun slotTimeMs(slot: Int): Long = ((slot + 0.5) * durationMs / slotCount).toLong()

    /** [timeMs] 落在哪一格。 */
    fun slotAt(timeMs: Long): Int = (timeMs.coerceIn(0, durationMs - 1) * slotCount / durationMs).toInt().coerceIn(0, slotCount - 1)

    /** 粗略预览的那几格，从片头到片尾。 */
    val coarseSlots: List<Int> = run {
        val count = minOf(COARSE_COUNT, slotCount)
        (0 until count).map { ((it + 0.5) * slotCount / count).toInt().coerceIn(0, slotCount - 1) }.distinct()
    }

    /**
     * 生成的先后，[positionMs] 是眼下播放到的地方：
     * 1. 当前位置那一格与左右各两格——人最可能马上去看的就是附近；
     * 2. 粗略预览，按对半细分的顺序（先片头、片中、片尾，再四分之一处……），做到哪一步全片都是均匀覆盖的；
     * 3. 其余各格，同样按对半细分的顺序往里补，直到补满。
     *
     * 不从 0% 顺着排到 100%：那样刚打开的几十秒里只有片头有图。
     */
    fun order(positionMs: Long): List<Int> {
        val seen = BooleanArray(slotCount)
        val result = ArrayList<Int>(slotCount)
        fun add(slot: Int) {
            if (slot in 0 until slotCount && !seen[slot]) {
                seen[slot] = true
                result += slot
            }
        }
        val here = slotAt(positionMs)
        add(here)
        for (step in 1..NEARBY_RADIUS) {
            add(here - step)
            add(here + step)
        }
        bisection(coarseSlots.size).forEach { add(coarseSlots[it]) }
        bisection(slotCount).forEach(::add)
        return result
    }

    /** 离 [timeMs] 最近的几格，由近及远。悬停到还没有图的地方时用它插队。 */
    fun around(timeMs: Long, radius: Int = NEARBY_RADIUS): List<Int> {
        val here = slotAt(timeMs)
        return (here - radius..here + radius).filter { it in 0 until slotCount }.sortedBy { abs(it - here) }
    }

    companion object {
        /** 粗略预览的张数。 */
        const val COARSE_COUNT = 24
        const val NEARBY_RADIUS = 2

        /** 再密就没有意义：转码流每 5 秒才有一个关键帧，相邻两格会取到同一帧。 */
        const val MIN_INTERVAL_MS = 3_000L
        private const val MIN_SLOTS = 8

        /**
         * 按片长与密度定张数。中档：30 分钟以内 60 张，30 到 60 分钟 90 张，1 到 2 小时 120 张，
         * 2 到 3 小时 180 张，更长 240 张；低档减半，高档加倍。短片按 [MIN_INTERVAL_MS] 封顶。
         */
        fun of(durationMs: Long, density: PreviewDensity): ThumbnailPlan {
            val minutes = durationMs / 60_000.0
            val medium = when {
                minutes <= 30 -> 60
                minutes <= 60 -> 90
                minutes <= 120 -> 120
                minutes <= 180 -> 180
                else -> 240
            }
            val wanted = when (density) {
                PreviewDensity.Low -> medium / 2
                PreviewDensity.Medium -> medium
                PreviewDensity.High -> medium * 2
            }
            val cap = (durationMs / MIN_INTERVAL_MS).toInt().coerceAtLeast(MIN_SLOTS)
            return ThumbnailPlan(durationMs, minOf(wanted, cap).coerceAtLeast(1))
        }

        /**
         * 0 到 count-1 的一个排列，按对半细分：两端与正中在前，然后各段的中点，一层层往里。
         * 取它的任何一个前缀，在整个区间上都是大致均匀的。
         */
        internal fun bisection(count: Int): List<Int> {
            if (count <= 0) return emptyList()
            val seen = BooleanArray(count)
            val result = ArrayList<Int>(count)
            fun add(index: Int) {
                if (!seen[index]) {
                    seen[index] = true
                    result += index
                }
            }
            add(0)
            add(count - 1)
            // 逐层把每一段从中间劈开
            var segments = listOf(0 to count - 1)
            while (segments.isNotEmpty()) {
                val next = ArrayList<Pair<Int, Int>>(segments.size * 2)
                for ((low, high) in segments) {
                    if (high - low < 2) continue
                    val middle = (low + high) / 2
                    add(middle)
                    next += low to middle
                    next += middle to high
                }
                segments = next
            }
            return result
        }
    }
}
