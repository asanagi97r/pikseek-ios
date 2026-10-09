package dev.pikseek.ui.rating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.piko.shared.state.FileRating
import dev.piko.ui.components.LocalPointerSource
import io.github.nihildigit.pikpak.FileStat

/** 收藏的红：比 Material 的 error 亮一点、偏粉，在深色封面上也醒目。 */
val LikedColor = Color(0xFFFF4D6D)

/** 讨厌的灰蓝：看得清，但不抢眼，讨厌的东西不该比收藏的还显眼。 */
val DislikedColor = Color(0xFFAEB7C4)

/** 没有 [LocalFileRatings] 时只认星标。 */
@Composable
fun ratingOf(file: FileStat): FileRating =
    LocalFileRatings.current?.ratingOf(file) ?: if (file.isStarred) FileRating.LIKED else FileRating.NONE

fun FileRating.icon(): ImageVector = when (this) {
    FileRating.NONE -> Icons.Outlined.FavoriteBorder
    FileRating.LIKED -> Icons.Filled.Favorite
    FileRating.DISLIKED -> Icons.Filled.HeartBroken
}

private fun FileRating.tint(neutral: Color): Color = when (this) {
    FileRating.NONE -> neutral
    FileRating.LIKED -> LikedColor
    FileRating.DISLIKED -> DislikedColor
}

/**
 * 卡片上的收藏按钮出不出来：标过的一直在；没标的用鼠标时悬停才出来，触屏上没有悬停，一直在。
 * 多选时不出来，点卡片是勾选。没有 [LocalFileRatings] 时不出来。
 */
@Composable
fun ratingButtonShown(file: FileStat, hovered: Boolean, isSelectionMode: Boolean): Boolean {
    val ratings = LocalFileRatings.current ?: return false
    if (isSelectionMode) return false
    return hovered || LocalPointerSource.current.isTouchLike || ratings.ratingOf(file) != FileRating.NONE
}

/** 一直占着位置的那种（标过的，或触屏上）。悬停才出来的浮在标签上面，不挤动标签。 */
@Composable
fun ratingButtonPinned(file: FileStat, isSelectionMode: Boolean): Boolean =
    ratingButtonShown(file, hovered = false, isSelectionMode = isSelectionMode)

private const val HINT = "单击收藏 · 双击讨厌"

/**
 * 收藏按钮：空心爱心；单击变实心（收藏），双击变心碎（讨厌），标过的再单击取消。
 * 变的时候弹一下。双击要等一小会儿才认得出单击，所以单击的反应比普通按钮慢约 0.3 秒。
 *
 * @param containerColor 圆底的颜色；浮在封面、画面上时给半透明的黑
 * @param size 圆底的直径
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RatingButton(
    file: FileStat,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.45f),
    neutralColor: Color = Color.White.copy(alpha = 0.92f),
    size: Dp = 28.dp,
    iconSize: Dp = 17.dp,
    tooltipBelow: Boolean = true,
) {
    val ratings = LocalFileRatings.current ?: return
    val rating = ratings.ratingOf(file)
    val pop = remember(file.id) { Animatable(1f) }
    val first = remember(file.id) { booleanArrayOf(true) }
    LaunchedEffect(rating) {
        // 第一次画出来不弹，只有点了才弹
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        pop.snapTo(0.6f)
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(HINT) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(containerColor)
                .pointerHoverIcon(PointerIcon.Hand)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    role = Role.Button,
                    onDoubleClick = { ratings.doubleClick(file) },
                    onClick = { ratings.click(file) },
                )
                .semantics { contentDescription = "${rating.label}。$HINT" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = rating.icon(),
                contentDescription = null,
                tint = rating.tint(neutralColor),
                modifier = Modifier.size(iconSize).scale(pop.value),
            )
        }
    }
}

/** 标题旁的小标记：收藏一颗红心、讨厌一颗心碎，没标时不画。列表视图里封面太小放不下按钮，靠它看出来。 */
@Composable
fun RatingMark(rating: FileRating, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    if (rating == FileRating.NONE) return
    Icon(
        imageVector = rating.icon(),
        contentDescription = if (rating == FileRating.LIKED) "已收藏" else "已讨厌",
        tint = rating.tint(Color.Unspecified),
        modifier = modifier.size(size),
    )
}

/**
 * 网盘页顶上的筛选：全部、收藏、讨厌、未标记，一排连在一起的小胶囊，点一下就切换，各带数量。
 * 选中的那一格填色。
 */
@Composable
fun RatingFilterBar(
    filter: FileRating?,
    counts: Map<FileRating, Int>,
    onChange: (FileRating?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, outline, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val options = listOf<FileRating?>(null, FileRating.LIKED, FileRating.DISLIKED, FileRating.NONE)
        options.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).height(30.dp).background(outline))
            FilterSegment(
                option = option,
                count = option?.let { counts[it] ?: 0 },
                selected = option == filter,
                onClick = { onChange(option) },
            )
        }
    }
}

@Composable
private fun FilterSegment(option: FileRating?, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = option?.label ?: "全部"
    Row(
        modifier = Modifier
            .height(30.dp)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = if (count != null) "只看$label，$count 个" else "全部显示" }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (option) {
            null -> Text("全部", fontSize = 13.sp, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
            else -> {
                Icon(
                    imageVector = option.icon(),
                    contentDescription = null,
                    tint = when (option) {
                        FileRating.NONE -> if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                        else -> option.tint(Color.Unspecified)
                    },
                    modifier = Modifier.size(15.dp),
                )
                if (option == FileRating.NONE) Text("未标", fontSize = 13.sp, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                if (count != null) Text("$count", fontSize = 12.sp, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}
