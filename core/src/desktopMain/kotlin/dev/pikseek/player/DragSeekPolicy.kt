package dev.pikseek.player

import dev.pikseek.platform.DragSeekMode
import kotlin.math.abs

/**
 * 拖着进度条的时候，主画面要不要跟着跳。纯计算：每次指针挪动问一次 [shouldSeek]。
 *
 * 网络流上每跳一次都要重开读取，跟着指针一路跳会把缓冲反复打断，所以默认只在「慢慢拖」时低频地跟：
 * - 慢慢拖（在找某个画面）：隔几百毫秒跟一次，像本地播放器那样有实时反馈；
 * - 快速扫过（只是路过）：一次都不跳，只看预览图；
 * - 松手时的那一次跳转由进度条自己发，不归这里管。
 */
class DragSeekPolicy {
    private var lastSampleMs = Long.MIN_VALUE
    private var lastSampleTarget = 0L
    private var lastSeekMs = Long.MIN_VALUE
    private var lastSeekTarget = Long.MIN_VALUE

    // 指针的速度，按「每秒扫过整条进度条的几分之几」算，做了平滑
    private var speed = 0.0

    /**
     * 指针到了 [targetMs]（片长 [durationMs]），眼下是 [nowMs]。返回 true 表示这一下该让主画面跳过去。
     */
    fun shouldSeek(mode: DragSeekMode, targetMs: Long, durationMs: Long, nowMs: Long): Boolean {
        if (durationMs <= 0) return false
        // 刚按下的第一个样本还看不出快慢
        val firstSample = lastSampleMs == Long.MIN_VALUE
        if (!firstSample) {
            val elapsed = (nowMs - lastSampleMs).coerceAtLeast(1)
            val instant = abs(targetMs - lastSampleTarget).toDouble() / durationMs / (elapsed / 1000.0)
            // 停住不动时速度要能很快落下来，所以新样本占大头
            speed = if (elapsed > STALE_SAMPLE_MS) instant else speed * 0.4 + instant * 0.6
        }
        lastSampleMs = nowMs
        lastSampleTarget = targetMs

        val interval = when (mode) {
            DragSeekMode.Off -> return false
            DragSeekMode.Always -> ALWAYS_INTERVAL_MS
            DragSeekMode.Adaptive -> {
                if (firstSample || speed > SLOW_SPEED) return false
                ADAPTIVE_INTERVAL_MS
            }
        }
        if (lastSeekMs != Long.MIN_VALUE && nowMs - lastSeekMs < interval) return false
        // 挪得太少不值得跳一次：至少一秒，长片按片长的千分之二
        val minimumMove = maxOf(MIN_MOVE_MS, durationMs / 500)
        if (lastSeekTarget != Long.MIN_VALUE && abs(targetMs - lastSeekTarget) < minimumMove) return false
        lastSeekMs = nowMs
        lastSeekTarget = targetMs
        return true
    }

    /** 松手了，下一次拖动重新算。 */
    fun reset() {
        lastSampleMs = Long.MIN_VALUE
        lastSeekMs = Long.MIN_VALUE
        lastSeekTarget = Long.MIN_VALUE
        speed = 0.0
    }

    companion object {
        /** 每秒扫过进度条的 8% 以内算「慢慢拖」。 */
        const val SLOW_SPEED = 0.08
        const val ADAPTIVE_INTERVAL_MS = 400L
        const val ALWAYS_INTERVAL_MS = 150L
        private const val MIN_MOVE_MS = 1_000L

        // 隔了这么久才来下一个样本，说明中间停住过，之前的速度不作数
        private const val STALE_SAMPLE_MS = 300L
    }
}
