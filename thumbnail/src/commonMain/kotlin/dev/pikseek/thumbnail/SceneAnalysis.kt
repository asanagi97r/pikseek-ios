package dev.pikseek.thumbnail

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

/**
 * 场景分点：把整部片切成至多 [MAX_CUTS] + 1 段，每段里的画面尽量一致、段与段之间尽量不同。纯计算，不碰网络与磁盘。
 *
 * 1. **粗分**：用预览缓存已有的那批帧（一两分钟一张），每张算一个小特征（颜色分布加明暗布局）。不看「相邻两张差多少」
 *    （镜头一晃就误报），而是在整条序列上找最优切法：切成 K 段，让每段内部离段平均最近。K 由一个惩罚项定：
 *    多切一段省下的差异要明显大于段内本来就有的起伏（由相邻帧的差估出来）才切。
 * 2. **细化**：粗分只知道切点在两张图之间，在那段里取中点的帧，看它更像前一段还是后一段，对半往下找，
 *    几次就缩到一个关键帧间隔以内。结果落在新一段的第一个关键帧上，拖过去一跳就到。
 *
 * 认得出的是画面整体颜色、明暗变了的地方：换房间、换衣服、换灯光、室内转室外。同一个房间、同样的衣服只换了动作，认不出。
 */
object SceneAnalysis {
    /** 一部片至多这么多个分点。 */
    const val MAX_CUTS = 10

    /** 一段至少这么长：再短的多半是插进来的一两个镜头，不算一段。 */
    const val MIN_SEGMENT_MS = 60_000L

    /** 细化到相邻两次取帧相差不到这么多就停：转码流约 5 秒一个关键帧，再细也取不到中间的帧。 */
    const val REFINE_PRECISION_MS = 6_000L

    /** 每个分点至多再取几帧。 */
    const val MAX_REFINE_STEPS = 5

    // 特征：色相 24 格 × 明暗 2 档，加 4 格灰阶（饱和度低或很暗的像素）；明暗布局 4×4。
    // 色相分得细一些才分得开紫光与蓝光这类布景灯：12 格时它们常落进同一格
    private const val HUE_BINS = 24
    private const val GRAY_BINS = 4
    private const val GRID = 4
    private const val GRID_WEIGHT = 0.35f
    private const val SAMPLE_STEP = 2

    /**
     * 多切一段要多省下多少差异，以段内起伏（一帧离段平均的平方距离）的倍数计，再乘 ln(帧数)。
     * 取值见 SceneAnalysisTest 与对真片的检查。
     */
    private const val PENALTY = 2.0

    val featureSize: Int = HUE_BINS * 2 + GRAY_BINS + GRID * GRID

    /**
     * 一帧的特征：颜色直方图开平方（两张图的欧氏距离就是 Hellinger 距离，对少量像素的变化不敏感），
     * 后面接 4×4 格的平均亮度（乘一个小权重：机位一换布局就变，只作辅助）。
     */
    fun feature(frame: ThumbnailFrame): FloatArray {
        val histogram = FloatArray(HUE_BINS * 2 + GRAY_BINS)
        val grid = FloatArray(GRID * GRID)
        val gridCount = IntArray(GRID * GRID)
        var total = 0f
        val width = frame.width
        val height = frame.height
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * GRID / height * GRID
            while (x < width) {
                val pixel = frame.pixels[y * width + x]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val high = max(red, max(green, blue))
                val low = min(red, min(green, blue))
                val value = high / 255f
                val saturation = if (high == 0) 0f else (high - low).toFloat() / high
                if (saturation < 0.25f || value < 0.2f) {
                    histogram[HUE_BINS * 2 + min(GRAY_BINS - 1, (value * GRAY_BINS).toInt())] += 1f
                    total += 1f
                } else {
                    val hue = hueOf(red, green, blue, high, low)
                    val bin = (hue / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                    histogram[bin * 2 + if (value < 0.6f) 0 else 1] += 1f
                    total += 1f
                }
                val cell = row + x * GRID / width
                grid[cell] += (red * 299 + green * 587 + blue * 114) / 255_000f
                gridCount[cell]++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        val out = FloatArray(featureSize)
        for (index in histogram.indices) out[index] = sqrt(histogram[index] / total.coerceAtLeast(1f))
        for (cell in grid.indices) out[histogram.size + cell] = GRID_WEIGHT * grid[cell] / gridCount[cell].coerceAtLeast(1)
        return out
    }

    private fun hueOf(red: Int, green: Int, blue: Int, high: Int, low: Int): Float {
        val delta = (high - low).toFloat()
        if (delta == 0f) return 0f
        val hue = when (high) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (index in a.indices) {
            val d = a[index] - b[index]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * 粗分。[features] 按时刻排好，[spanMs] 是相邻两帧大约隔多远。返回切点：值 b 表示第 b 帧是新一段的第一帧。
     */
    fun coarseCuts(
        features: List<FloatArray>,
        spanMs: Long,
        maxCuts: Int = MAX_CUTS,
        minSegmentMs: Long = MIN_SEGMENT_MS,
        penaltyFactor: Double = PENALTY,
    ): List<Int> {
        val n = features.size
        val minFrames = max(2, ceil(minSegmentMs.toDouble() / spanMs.coerceAtLeast(1)).toInt())
        val maxSegments = min(maxCuts + 1, n / minFrames)
        if (maxSegments < 2) return emptyList()
        val dimension = features[0].size

        // 前缀和：任意一段的「各帧离段平均的平方距离之和」都能 O(维数) 算出
        val prefix = Array(n + 1) { DoubleArray(dimension) }
        val prefixSquares = DoubleArray(n + 1)
        for (i in 0 until n) {
            var squares = 0.0
            for (d in 0 until dimension) {
                val v = features[i][d].toDouble()
                prefix[i + 1][d] = prefix[i][d] + v
                squares += v * v
            }
            prefixSquares[i + 1] = prefixSquares[i] + squares
        }
        // 第 from 帧到第 to 帧（不含）的代价。先算好，下面的动态规划里要反复用
        val cost = Array(n + 1) { DoubleArray(n + 1) }
        for (from in 0 until n) {
            for (to in from + minFrames..n) {
                var sumSquared = 0.0
                for (d in 0 until dimension) {
                    val s = prefix[to][d] - prefix[from][d]
                    sumSquared += s * s
                }
                cost[from][to] = (prefixSquares[to] - prefixSquares[from]) - sumSquared / (to - from)
            }
        }

        // best[k][j]：前 j 帧切成 k 段的最小代价
        val best = Array(maxSegments + 1) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        val from = Array(maxSegments + 1) { IntArray(n + 1) { -1 } }
        for (j in minFrames..n) best[1][j] = cost[0][j]
        for (k in 2..maxSegments) {
            for (j in k * minFrames..n) {
                var bestCost = Double.MAX_VALUE
                var bestStart = -1
                for (i in (k - 1) * minFrames..j - minFrames) {
                    val previous = best[k - 1][i]
                    if (previous == Double.MAX_VALUE) continue
                    val candidate = previous + cost[i][j]
                    if (candidate < bestCost) {
                        bestCost = candidate
                        bestStart = i
                    }
                }
                best[k][j] = bestCost
                from[k][j] = bestStart
            }
        }

        // 段内起伏：相邻两帧差的平方，取中位数的一半（跨段的那几对是少数，中位数不受它们影响）
        val neighbours = (0 until n - 1).map { i ->
            var sum = 0.0
            for (d in 0 until dimension) {
                val diff = (features[i + 1][d] - features[i][d]).toDouble()
                sum += diff * diff
            }
            sum
        }.sorted()
        val variation = (neighbours[neighbours.size / 2] / 2).coerceAtLeast(MIN_VARIATION)
        val penalty = penaltyFactor * variation * ln(n.toDouble())

        var chosen = 1
        var chosenScore = best[1][n]
        for (k in 2..maxSegments) {
            if (best[k][n] == Double.MAX_VALUE) continue
            val score = best[k][n] + penalty * (k - 1)
            if (score < chosenScore) {
                chosen = k
                chosenScore = score
            }
        }
        val cuts = ArrayList<Int>()
        var end = n
        for (k in chosen downTo 2) {
            val start = from[k][end]
            cuts += start
            end = start
        }
        return cuts.sorted()
    }

    // 全片几乎一样（纯色测试片）时起伏是 0，惩罚也成了 0，任何一点差异都会被切开
    private const val MIN_VARIATION = 1e-4

    /**
     * 把粗分的一个切点细化：切点在 [leftTimeMs]（前一段的最后一帧）与 [rightTimeMs]（后一段的第一帧）之间，
     * [left]、[right] 是前后两段挨着切点的那几帧的平均特征。返回新一段第一帧的时刻。
     * [fetch] 取某个时刻附近的一帧，取不到为 null（那就停在已知的范围上）。
     */
    suspend fun refine(
        left: FloatArray,
        right: FloatArray,
        leftTimeMs: Long,
        rightTimeMs: Long,
        fetch: suspend (Long) -> ThumbnailFrame?,
    ): Long {
        var low = leftTimeMs
        var high = rightTimeMs
        repeat(MAX_REFINE_STEPS) {
            if (high - low <= REFINE_PRECISION_MS) return high
            val frame = try {
                fetch((low + high) / 2)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return high
            val time = frame.timeMs
            // 取帧落在关键帧上：中间没有别的关键帧时会落回两端，再找也找不出新东西
            if (time <= low || time >= high) return high
            when {
                // 黑场多半就是转场：从它算新一段的开头
                frame.isBlack() -> high = time
                distance(feature(frame), left) <= distance(feature(frame), right) -> low = time
                else -> high = time
            }
        }
        return high
    }

    /**
     * 整套：[frames] 是预览的帧（顺序不论），[durationMs] 是片长。[fetch] 为 null 时只粗分，分点落在新一段的第一张预览图上。
     * [onProgress] 报细化做到第几个分点。返回分点时刻，升序。
     */
    suspend fun analyze(
        frames: List<ThumbnailFrame>,
        durationMs: Long,
        fetch: (suspend (Long) -> ThumbnailFrame?)?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Long> {
        val sorted = frames.sortedBy { it.timeMs }.distinctBy { it.timeMs }
        if (sorted.size < 4 || durationMs <= 0) return emptyList()
        val features = sorted.map(::feature)
        val span = durationMs / sorted.size
        val cuts = coarseCuts(features, span)
        val bounds = listOf(0) + cuts + sorted.size
        val result = ArrayList<Long>()
        cuts.forEachIndexed { index, cut ->
            onProgress(index, cuts.size)
            val segmentStart = bounds[index]
            val segmentEnd = bounds[index + 2]
            // 粗分可能差一张：紧挨着切点的那两张本身就可能是两段之间的过渡。细化的范围往两边各放宽一张（不越过别的分点），
            // 「前一段」「后一段」的样子取再往外的几张
            val low = max(segmentStart, cut - 2)
            val high = min(segmentEnd - 1, cut + 1)
            val left = mean(features.subList(max(segmentStart, low - NEAR_FRAMES + 1), low + 1))
            val right = mean(features.subList(high, min(segmentEnd, high + NEAR_FRAMES)))
            val time = if (fetch == null) sorted[cut].timeMs else refine(left, right, sorted[low].timeMs, sorted[high].timeMs, fetch)
            result += time
        }
        onProgress(cuts.size, cuts.size)
        return result.filter { it in 1 until durationMs }.distinct().sorted()
    }

    // 细化时拿切点两侧各几帧的平均当「前一段」「后一段」的样子：整段的平均会被段里别的机位带偏
    private const val NEAR_FRAMES = 2

    private fun mean(features: List<FloatArray>): FloatArray {
        val out = FloatArray(features[0].size)
        for (feature in features) for (index in out.indices) out[index] += feature[index]
        for (index in out.indices) out[index] /= features.size
        return out
    }
}
