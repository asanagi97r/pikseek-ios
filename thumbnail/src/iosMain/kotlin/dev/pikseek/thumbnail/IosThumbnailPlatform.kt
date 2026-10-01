package dev.pikseek.thumbnail

import dev.pikseek.platform.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
internal actual fun fileLastModifiedMillis(path: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
    val date = attributes[NSFileModificationDate] as? NSDate ?: return 0L
    return (date.timeIntervalSince1970 * 1000).toLong()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun touchFile(path: String, millis: Long) {
    NSFileManager.defaultManager.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)),
        ofItemAtPath = path,
        error = null,
    )
}

/**
 * Swift 一侧交回来的一帧：`screenshot-raw` 的原样数据，bgr0，每行 [stride] 字节。
 * [rotate]、[displayWidth]、[displayHeight] 是这个视频的旋转元数据与显示尺寸，没有时为 0。
 */
class RawFrame(
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val stride: Int,
    val data: NSData,
    val rotate: Int,
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * iOS 上的取帧解码器，Swift 实现（iosApp 里的 MpvFrameGrabber.swift）：一个不出画面、不出声音的 libmpv 实例。
 * 语义与 [FrameGrabber] 相同，只是帧以原始字节交回，由这边转成 [ThumbnailFrame]。
 */
interface NativeFrameGrabber {
    fun durationMs(): Long

    /** [startSeconds] 小于 0 表示从头打开。 */
    fun open(location: String, timeoutMs: Long, startSeconds: Double): Boolean

    fun grab(): RawFrame?

    fun seekAndGrab(seconds: Double, timeoutMs: Long): RawFrame?

    fun close()
}

/** 程序启动时由 Swift 一侧交进来：每要一个解码器就调一次。建不出来返回 null。 */
object IosThumbnailPlatform {
    var grabberFactory: ((frameWidth: Int) -> NativeFrameGrabber?)? = null

    fun newGrabber(frameWidth: Int = FrameGrabber.DEFAULT_WIDTH): FrameGrabber? =
        grabberFactory?.invoke(frameWidth)?.let(::BridgedFrameGrabber)
}

private class BridgedFrameGrabber(private val native: NativeFrameGrabber) : FrameGrabber {
    private var closed = false

    override val durationMs: Long get() = native.durationMs()

    override fun open(location: String, timeoutMs: Long, startSeconds: Double?): Boolean {
        check(!closed) { "已关闭" }
        return native.open(location, timeoutMs, startSeconds ?: -1.0)
    }

    override fun grab(): ThumbnailFrame? {
        check(!closed) { "已关闭" }
        return native.grab()?.let(::convert)
    }

    override fun seekAndGrab(seconds: Double, timeoutMs: Long): ThumbnailFrame? {
        check(!closed) { "已关闭" }
        return native.seekAndGrab(seconds, timeoutMs)?.let(::convert)
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private fun convert(raw: RawFrame): ThumbnailFrame? {
        val width = raw.width
        val height = raw.height
        val stride = raw.stride
        if (width <= 0 || height <= 0 || stride < width * 4) return null
        val bytes = raw.data.toByteArray()
        if (bytes.size.toLong() < stride.toLong() * height) return null
        // bgr0：内存里依次是 B、G、R、填充
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            var at = y * stride
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = OPAQUE or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    (bytes[at].toInt() and 0xFF)
                at += 4
            }
        }
        return ThumbnailFrame(raw.timeMs, width, height, pixels).uprightFor(raw.rotate, raw.displayWidth, raw.displayHeight)
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}
