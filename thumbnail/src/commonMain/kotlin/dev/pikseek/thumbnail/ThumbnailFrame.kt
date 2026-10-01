package dev.pikseek.thumbnail

import dev.pikseek.security.Digests

/**
 * 一张解码好的缩略图。[pixels] 按行排列，每个像素一个 Int：0xAARRGGBB，A 恒为 0xFF。
 * [timeMs] 是这一帧在片中的真实时刻，不是要它的那个时刻：取帧只能落在关键帧上。
 */
class ThumbnailFrame(val timeMs: Long, val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "像素数与尺寸对不上" }
    }

    /**
     * 明显是全黑的一帧：平均亮度很低，而且各处差不多一样黑。
     *
     * 只看亮度会把夜景也算进去；夜景里总有几处亮的（灯、字幕、脸），亮度的方差不小。
     * 两个条件都满足才算黑帧。真正的黑场（转场、片头）照样会被判成黑帧，所以调用方只是往后挪一点重取，
     * 重取几次仍是黑的就接受它。
     */
    fun isBlack(): Boolean {
        var sum = 0L
        var sumOfSquares = 0L
        // 隔几个像素取一个就够判断了
        var count = 0
        var index = 0
        while (index < pixels.size) {
            val pixel = pixels[index]
            val luma = (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
            sum += luma
            sumOfSquares += luma.toLong() * luma
            count++
            index += SAMPLE_STEP
        }
        val mean = sum.toDouble() / count
        val variance = sumOfSquares.toDouble() / count - mean * mean
        return mean < BLACK_MEAN && variance < BLACK_VARIANCE
    }

    /**
     * 竖拍的片子带着旋转元数据，画面输出时才转；软件截图拿到的是没转的。
     * 截图的横竖与该显示的横竖对不上时，照元数据转过来。[rotate] 是角度，[displayWidth]、[displayHeight] 是该显示的尺寸。
     */
    fun uprightFor(rotate: Int, displayWidth: Int, displayHeight: Int): ThumbnailFrame {
        val angle = rotate.mod(360)
        if (angle != 90 && angle != 270) return this
        if (displayWidth <= 0 || displayHeight <= 0) return this
        val alreadyUpright = (width >= height) == (displayWidth >= displayHeight)
        if (alreadyUpright) return this
        val out = IntArray(pixels.size)
        val newWidth = height
        val newHeight = width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                if (angle == 90) out[x * newWidth + (newWidth - 1 - y)] = pixel else out[(newHeight - 1 - x) * newWidth + y] = pixel
            }
        }
        return ThumbnailFrame(timeMs, newWidth, newHeight, out)
    }

    private companion object {
        const val SAMPLE_STEP = 7
        const val BLACK_MEAN = 12.0
        const val BLACK_VARIANCE = 20.0
    }
}

/** 把缩略图拼成的一整张图压成文件、再解回来。由界面那一侧提供（Skia 的 WebP），缩略图模块自己不带图像库。 */
interface SpriteCodec {
    /** 文件扩展名，不带点，例如 "webp"。 */
    val extension: String

    fun encode(width: Int, height: Int, pixels: IntArray): ByteArray

    /** 解不出来返回 null。 */
    fun decode(bytes: ByteArray): DecodedSprite?
}

class DecodedSprite(val width: Int, val height: Int, val pixels: IntArray)

/**
 * 一个视频的指纹，缓存据此认它。不用播放地址：直链带签名，几小时就换。
 *
 * [contentHash] 是 PikPak 的 gcid（内容哈希），同一份内容换了文件名、换了目录都认得出；
 * 没有时（本机文件）退回 [fileId]。再带上大小与时长，内容被替换时缓存自然作废。
 */
data class MediaFingerprint(
    val fileId: String,
    val contentHash: String,
    val sizeBytes: Long,
    val durationMs: Long,
) {
    /** 缓存目录名：指纹的摘要，不含文件名。 */
    fun cacheKey(): String {
        val identity = contentHash.ifBlank { "id:$fileId" }
        return Digests.hex(Digests.sha256("$identity|$sizeBytes|$durationMs|v$FORMAT_VERSION".encodeToByteArray())).take(32)
    }

    companion object {
        /** 缓存格式的版本。改了雪碧图或索引的格式就加一，旧缓存自动作废。 */
        const val FORMAT_VERSION = 1
    }
}
