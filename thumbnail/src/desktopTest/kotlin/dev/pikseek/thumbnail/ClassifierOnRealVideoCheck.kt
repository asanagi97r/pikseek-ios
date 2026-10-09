package dev.pikseek.thumbnail

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * 试分类模型 yolo26n-cls-porn：按中档预览的格子从一部真片取帧，逐帧分类，量速度，看标签连成的章节。
 * 设了 PIKSEEK_LONG_SAMPLE（视频路径）、模型在 pikseek.models 下才跑；PIKSEEK_SHEET_OUT 给了就拼一张带标签的总览图。
 */
class ClassifierOnRealVideoCheck {
    private val modelDirectory = File(System.getProperty("pikseek.models"), "yolo26n-cls-porn")

    @Test
    fun classifyPreviewFrames() = runBlocking {
        val path = System.getenv("PIKSEEK_LONG_SAMPLE")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val model = File(modelDirectory, "best.onnx").takeIf { it.isFile } ?: return@runBlocking
        val thresholds = Regex("\"([^\"]+)\":([0-9.]+)").findAll(File(modelDirectory, "class35_conf_thresholds_by_name.json").readText())
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }

        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        val loadStarted = System.nanoTime()
        val session = environment.createSession(model.absolutePath, options)
        val names = parseNames(session.metadata.customMetadata["names"].orEmpty())
        println("模型载入 ${(System.nanoTime() - loadStarted) / 1_000_000} ms，${names.size} 类，输入 ${session.inputInfo.keys} 输出 ${session.outputInfo.keys}")

        val frames = MpvFrameGrabber(TestMedia.mpvDirectory).use { grabber ->
            check(grabber.open(path))
            val plan = ThumbnailPlan.of(grabber.durationMs, PreviewDensity.Medium)
            val source = SeekingSource(path, grabber, "本机")
            (0 until plan.slotCount).mapNotNull { source.frameNear(plan.slotTimeMs(it)) }.sortedBy { it.timeMs }
        }

        val input = session.inputNames.first()
        val results = ArrayList<Triple<ThumbnailFrame, Pair<String, Float>, Boolean>>()
        // 先跑一张热身，不计时
        classify(environment, session, input, frames.first())
        val started = System.nanoTime()
        for (frame in frames) {
            val probabilities = classify(environment, session, input, frame)
            val order = probabilities.indices.sortedByDescending { probabilities[it] }
            val top1 = names[order[0]] ?: "?"
            val p1 = probabilities[order[0]]
            val p2 = probabilities[order[1]]
            val accepted = p1 >= (thresholds[top1] ?: 0.5f) && p1 - p2 >= 0.4f
            results += Triple(frame, top1 to p1, accepted)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        println("分类 ${frames.size} 帧：共 $elapsed ms，每帧 ${elapsed / frames.size.coerceAtLeast(1)} ms")
        println("逐帧（时刻 标签 概率 *=过阈值）：")
        println(results.joinToString("  ") { (frame, top, ok) -> "${clock(frame.timeMs)} ${top.first} ${"%.2f".format(top.second)}${if (ok) "*" else ""}" })
        println("过阈值的帧：${results.count { it.third }} / ${results.size}，各标签：" +
            results.filter { it.third }.groupingBy { it.second.first }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("不看阈值的 top1 分布：" + results.groupingBy { it.second.first }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        System.getenv("PIKSEEK_SHEET_OUT")?.takeIf { it.isNotBlank() }?.let { sheet(results, Path.of(it)) }
        session.close()
    }

    /** 元数据里的类名：`{0: 'AI生成', 1: '乳交', ...}`。 */
    private fun parseNames(text: String): Map<Int, String> =
        Regex("(\\d+):\\s*'([^']*)'").findAll(text).associate { it.groupValues[1].toInt() to it.groupValues[2] }

    /** 照 Ultralytics 分类的预处理：短边缩到 224、居中裁 224×224、RGB 0–1。 */
    private fun classify(environment: OrtEnvironment, session: OrtSession, input: String, frame: ThumbnailFrame): FloatArray {
        val size = 224
        val scale = size.toFloat() / minOf(frame.width, frame.height)
        val scaledWidth = frame.width * scale
        val scaledHeight = frame.height * scale
        val offsetX = (scaledWidth - size) / 2
        val offsetY = (scaledHeight - size) / 2
        val data = FloatArray(3 * size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // 双线性取样
                val sx = ((x + offsetX + 0.5f) / scale - 0.5f).coerceIn(0f, frame.width - 1f)
                val sy = ((y + offsetY + 0.5f) / scale - 0.5f).coerceIn(0f, frame.height - 1f)
                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = minOf(x0 + 1, frame.width - 1)
                val y1 = minOf(y0 + 1, frame.height - 1)
                val fx = sx - x0
                val fy = sy - y0
                for (channel in 0 until 3) {
                    val shift = 16 - channel * 8
                    fun at(px: Int, py: Int) = ((frame.pixels[py * frame.width + px] shr shift) and 0xFF).toFloat()
                    val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
                    val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
                    data[channel * size * size + y * size + x] = (top * (1 - fy) + bottom * fy) / 255f
                }
            }
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val value = output[0].value as Array<FloatArray>
                return value[0]
            }
        }
    }

    private fun clock(ms: Long): String = "%d:%02d:%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)

    private fun sheet(results: List<Triple<ThumbnailFrame, Pair<String, Float>, Boolean>>, out: Path) {
        val cellWidth = 160
        val cellHeight = 90
        val label = 16
        val columns = 10
        val rows = (results.size + columns - 1) / columns
        val image = BufferedImage(columns * cellWidth, rows * (cellHeight + label), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.font = Font("Microsoft YaHei", Font.PLAIN, 11)
        results.forEachIndexed { index, (frame, top, ok) ->
            val x = index % columns * cellWidth
            val y = index / columns * (cellHeight + label)
            val picture = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            picture.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
            g.drawImage(picture, x, y + label, cellWidth, cellHeight, null)
            g.color = if (ok) Color(0x2E7D32) else Color(0x424242)
            g.fillRect(x, y, cellWidth, label)
            g.color = Color.WHITE
            g.drawString("${clock(frame.timeMs)} ${top.first} ${"%.2f".format(top.second)}", x + 3, y + 12)
        }
        g.dispose()
        ImageIO.write(image, "png", out.toFile())
        println("总览图：$out")
    }
}
