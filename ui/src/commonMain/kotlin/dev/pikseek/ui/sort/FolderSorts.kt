package dev.pikseek.ui.sort

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.state.SortMemory
import dev.piko.ui.screens.drive.SortButton
import dev.pikseek.platform.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * 各文件夹的排序，存在本机的设置里（[AppSettings.sortFor]）：
 * 设了默认排序，所有文件夹都按它；在某个文件夹里改了，记住这个文件夹的（记忆开着时）；
 * 再设一次默认排序，记住的全部清掉，重新都按默认。网盘页与播放器的选集共用。
 */
class FolderSorts(private val settings: AppSettings) : SortMemory {
    /** 默认排序或记忆开关改了时变，开着的文件列表看它重排。 */
    val versions: StateFlow<Int> get() = settings.sortVersion

    override fun orderFor(folderId: String): PikoFileSortOrder = parse(settings.sortFor(folderId))

    override fun remember(folderId: String, order: PikoFileSortOrder) = settings.setFolderSort(folderId, order.name)

    companion object {
        fun parse(name: String): PikoFileSortOrder = PikoFileSortOrder.entries.firstOrNull { it.name == name } ?: PikoFileSortOrder.TIME_DESC
    }
}

/** 没提供时为 null：别的窗口、测试里排序不记，照 Piko 原来的样子。 */
val LocalSortMemory = staticCompositionLocalOf<FolderSorts?> { null }

/** 一种排序的说法，设置页与菜单里用：「创建时间 · 新的在前」。 */
fun PikoFileSortOrder.describe(): String = when (this) {
    PikoFileSortOrder.TIME_DESC -> "创建时间 · 新的在前"
    PikoFileSortOrder.TIME_ASC -> "创建时间 · 旧的在前"
    PikoFileSortOrder.NAME_ASC -> "名称 · 正序（第 1 集在前）"
    PikoFileSortOrder.NAME_DESC -> "名称 · 倒序"
    PikoFileSortOrder.SIZE_DESC -> "大小 · 大的在前"
    PikoFileSortOrder.SIZE_ASC -> "大小 · 小的在前"
}

/**
 * 播放器选集列表的排序：眼下怎么排、改了之后怎么办。播放窗口建一份，经 [LocalPlaylistSort] 交给选集面板。
 * 改了记到视频所在文件夹名下，与网盘里那个文件夹是同一份记忆。
 */
class PlaylistSort {
    /** 还不知道视频在哪个文件夹时为 null，面板不给排序按钮。 */
    var order by mutableStateOf<PikoFileSortOrder?>(null)

    var onChange: (PikoFileSortOrder) -> Unit = {}
}

val LocalPlaylistSort = staticCompositionLocalOf<PlaylistSort?> { null }

/** 选集面板顶上的排序按钮，与网盘页的是同一个。上一集、下一集与连播都跟着这个顺序走。 */
@Composable
fun PlaylistSortRow(modifier: Modifier = Modifier) {
    val sort = LocalPlaylistSort.current ?: return
    val order = sort.order ?: return
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SortButton(order, sort.onChange)
        Text(
            "上一集、下一集跟着这个顺序",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
