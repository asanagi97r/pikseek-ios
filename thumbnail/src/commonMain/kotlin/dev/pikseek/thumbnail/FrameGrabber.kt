package dev.pikseek.thumbnail

/**
 * 解码取帧的那一头：一个不出画面、不出声音的解码器实例，与主播放器完全分开。
 * 桌面是经 FFM 直调程序自带的 libmpv（MpvFrameGrabber），iOS 是 Swift 一侧包的 libmpv。
 *
 * 不是线程安全的：一个实例同一时刻只由一个线程用。方法都会阻塞，调用方放在后台线程上。
 */
interface FrameGrabber : AutoCloseable {
    /** 最近一次 [open] 的文件时长，毫秒；不知道为 0。 */
    val durationMs: Long

    /**
     * 打开 [location]（本机路径或网址），停在第一帧上。打不开或 [timeoutMs] 内没出第一帧返回 false。
     * [startSeconds] 非空时直接从那里（最近的关键帧）打开。
     */
    fun open(location: String, timeoutMs: Long = OPEN_TIMEOUT_MS, startSeconds: Double? = null): Boolean

    /** 眼下停着的那一帧。没有画面时返回 null。 */
    fun grab(): ThumbnailFrame?

    /**
     * 跳到 [seconds] 之前最近的关键帧并取那一帧。跳到关键帧而不是精确时刻：精确定位要从关键帧一路解到目标，
     * 网络流上慢得多，而缩略图差几秒无所谓，帧的真实时刻记在返回值里。
     */
    fun seekAndGrab(seconds: Double, timeoutMs: Long = SEEK_TIMEOUT_MS): ThumbnailFrame?

    companion object {
        const val DEFAULT_WIDTH = 240
        const val OPEN_TIMEOUT_MS = 20_000L
        const val SEEK_TIMEOUT_MS = 20_000L
    }
}
