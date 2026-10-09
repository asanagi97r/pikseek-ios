package dev.pikseek.thumbnail

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * 拿一部真片看场景分点切得怎么样：按中档预览的格子取帧，粗分、细化，拼一张总览图（每段一个颜色，分点处红框）。
 * 设了 PIKSEEK_LONG_SAMPLE（视频路径）与 PIKSEEK_SHEET_OUT（总览图存到哪）才跑；平时跳过。
 */
class SceneOnRealVideoCheck {
    @Test
    fun contactSheet() = runBlocking {
        val path = System.getenv("PIKSEEK_LONG_SAMPLE")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val out = System.getenv("PIKSEEK_SHEET_OUT")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val density = System.getenv("PIKSEEK_DENSITY")?.let { PreviewDensity.valueOf(it) } ?: PreviewDensity.Medium
        MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            check(grabber.open(path))
            val duration = grabber.durationMs
            val plan = ThumbnailPlan.of(duration, density)
            val source = SeekingSource(path, grabber, "本机")
            val started = System.nanoTime()
            val frames = (0 until plan.slotCount).mapNotNull { source.frameNear(plan.slotTimeMs(it)) }
            println("片长 ${duration / 1000} 秒，${frames.size} 帧，取帧 ${(System.nanoTime() - started) / 1_000_000} ms")

            val sortedFrames = frames.sortedBy { it.timeMs }
            val features = sortedFrames.map(SceneAnalysis::feature)
            for (factor in listOf(1.0, 1.5, 2.0, 3.0)) {
                val cuts = SceneAnalysis.coarseCuts(features, duration / frames.size, penaltyFactor = factor)
                println("惩罚 $factor：${cuts.joinToString { clock(sortedFrames[it].timeMs) }}")
            }
            for (k in 1..10) {
                val cuts = SceneAnalysis.coarseCuts(features, duration / frames.size, maxCuts = k, penaltyFactor = 0.0)
                println("强切 $k 刀：${cuts.joinToString { clock(sortedFrames[it].timeMs) }}")
            }
            val coarse = SceneAnalysis.analyze(frames, duration, fetch = null)
            var fetches = 0
            val refineStart = System.nanoTime()
            val refined = SceneAnalysis.analyze(frames, duration, fetch = { fetches++; source.frameNear(it) })
            println("粗分：${coarse.joinToString { clock(it) }}")
            println("细化：${refined.joinToString { clock(it) }}（多取 $fetches 帧，${(System.nanoTime() - refineStart) / 1_000_000} ms）")
            sheet(frames.sortedBy { it.timeMs }, coarse, refined, Path.of(out))
        }
    }

    private fun clock(ms: Long): String = "%d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)

    private fun sheet(frames: List<ThumbnailFrame>, coarse: List<Long>, refined: List<Long>, out: Path) {
        val cellWidth = 160
        val cellHeight = 90
        val columns = 10
        val label = 14
        val rows = (frames.size + columns - 1) / columns
        val image = BufferedImage(columns * cellWidth, rows * (cellHeight + label), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        val palette = listOf(Color(0xE53935), Color(0x1E88E5), Color(0x43A047), Color(0xFB8C00), Color(0x8E24AA), Color(0x00ACC1), Color(0xFDD835), Color(0x6D4C41), Color(0xD81B60), Color(0x3949AB), Color(0x7CB342))
        frames.forEachIndexed { index, frame ->
            val x = index % columns * cellWidth
            val y = index / columns * (cellHeight + label)
            val picture = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            picture.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
            g.drawImage(picture, x, y + label, cellWidth, cellHeight, null)
            val segment = coarse.count { it <= frame.timeMs }
            g.color = palette[segment % palette.size]
            g.fillRect(x, y, cellWidth, label)
            g.color = Color.WHITE
            g.drawString("${clock(frame.timeMs)}  段${segment + 1}", x + 3, y + 11)
            if (frame.timeMs in coarse) {
                g.color = Color.RED
                g.stroke = BasicStroke(4f)
                g.drawRect(x + 2, y + label + 2, cellWidth - 4, cellHeight - 4)
            }
        }
        g.dispose()
        ImageIO.write(image, "png", out.toFile())
        println("总览图：$out（细化后的分点 ${refined.size} 个）")
    }
}
