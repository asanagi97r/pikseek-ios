package dev.pikseek.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.pikseek.thumbnail.DecodedSprite
import dev.pikseek.thumbnail.SpriteCodec
import dev.pikseek.thumbnail.ThumbnailFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * 缩略图模块只认「一个 Int 一个像素」的数组；与图像库打交道的事都在这里，用界面本来就带着的 Skia。
 *
 * 像素是 0xAARRGGBB，按小端写进内存正好是 B、G、R、A 的顺序，即 Skia 的 BGRA_8888，不必逐像素换位。
 */
private fun info(width: Int, height: Int) = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

private fun bytesOf(pixels: IntArray): ByteArray {
    val bytes = ByteArray(pixels.size * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
    return bytes
}

/** 雪碧图存成 WebP：同样的清晰度比 JPEG 小三成左右，一个两小时的片子的整套预览在一两兆。 */
object WebpSpriteCodec : SpriteCodec {
    private const val QUALITY = 78

    override val extension: String = "webp"

    override fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val image = Image.makeRaster(info(width, height), bytesOf(pixels), width * 4)
        try {
            val data = image.encodeToData(EncodedImageFormat.WEBP, QUALITY) ?: error("WebP 编码失败")
            return try {
                data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }

    override fun decode(bytes: ByteArray): DecodedSprite? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        try {
            val bitmap = Bitmap()
            try {
                if (!bitmap.allocPixels(info(image.width, image.height)) || !image.readPixels(bitmap)) return null
                val raw = bitmap.readPixels(info(image.width, image.height), image.width * 4) ?: return null
                val pixels = IntArray(image.width * image.height)
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
                // 不透明：解出来的 alpha 一律当 0xFF
                for (index in pixels.indices) pixels[index] = pixels[index] or 0xFF000000.toInt()
                DecodedSprite(image.width, image.height, pixels)
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }.getOrNull()
}

/** 一帧缩略图转成界面能画的位图。 */
fun ThumbnailFrame.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(info(width, height))
    bitmap.installPixels(bytesOf(pixels))
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
