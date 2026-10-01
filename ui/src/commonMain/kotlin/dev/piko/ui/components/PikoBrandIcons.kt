package dev.piko.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * PikSeek 的品牌图形：一条进度条，手柄上方浮着一格预览画面。与 docs/icon.svg、程序图标同源
 * （程序图标由 docs/make_icon.py 按同一组坐标画出）。
 *
 * 对象名沿用 Piko 的，调用处不必改；图形是 PikSeek 自己的，不用 Piko 的猫脸，免得与原版混淆。
 *
 * 写成 ImageVector 而不走 Compose 资源：只有两张图，为它们引入资源插件与生成的 Res 类不值得。
 */
object PikoBrandIcons {
    // 预览画面的外框
    internal const val FRAME = "M15,9 H33 A3,3 0 0 1 36,12 V22 A3,3 0 0 1 33,25 H15 A3,3 0 0 1 12,22 V12 A3,3 0 0 1 15,9 Z"

    // 画面里的播放三角
    internal const val PLAY = "M21.6,13.4 L27.8,17 L21.6,20.6 Z"

    // 外框指向手柄的小尖
    internal const val TAIL = "M21,25.6 L27,25.6 L24,29.2 Z"

    // 进度条：整条、已播放的一段、手柄
    internal const val TRACK = "M9,35.5 H39"
    internal const val PLAYED = "M9,35.5 H24"
    internal const val THUMB = "M24,31.9 A3.6,3.6 0 1 1 24,39.1 A3.6,3.6 0 1 1 24,31.9 Z"
    internal const val BADGE =
        "M12,0 H36 A12,12 0 0 1 48,12 V36 A12,12 0 0 1 36,48 H12 A12,12 0 0 1 0,36 V12 A12,12 0 0 1 12,0 Z"

    internal val BrandViolet = Color(0xFF6750F5)

    /** 圆角品牌色底上的白色图形，登录页用。 */
    val Logo: ImageVector by lazy {
        ImageVector.Builder("PikSeekLogo", 48.dp, 48.dp, 48f, 48f).apply {
            addPath(addPathNodes(BADGE), fill = SolidColor(BrandViolet))
            mark(Color.White)
        }.build()
    }

    /** 单色标志，随所在位置的内容色着色。 */
    val Glyph: ImageVector by lazy {
        ImageVector.Builder("PikSeekGlyph", 24.dp, 24.dp, 48f, 48f).apply {
            mark(Color.Black)
        }.build()
    }

    internal fun ImageVector.Builder.mark(color: Color) {
        val brush = SolidColor(color)
        addPath(addPathNodes(FRAME), stroke = brush, strokeLineWidth = 2.6f, strokeLineJoin = StrokeJoin.Round)
        addPath(addPathNodes(PLAY), fill = brush, stroke = brush, strokeLineWidth = 1.2f, strokeLineJoin = StrokeJoin.Round)
        addPath(addPathNodes(TAIL), fill = brush)
        addPath(addPathNodes(TRACK), stroke = brush, strokeAlpha = 0.45f, strokeLineWidth = 2.8f, strokeLineCap = StrokeCap.Round)
        addPath(addPathNodes(PLAYED), stroke = brush, strokeLineWidth = 2.8f, strokeLineCap = StrokeCap.Round)
        addPath(addPathNodes(THUMB), fill = brush)
    }
}
