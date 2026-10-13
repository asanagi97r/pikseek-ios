package dev.piko.shared.state

import dev.piko.shared.data.PikoFileSortOrder

/**
 * PikSeek：每个文件夹该怎么排。进文件夹时问它，在文件夹里改了排序时告诉它；
 * 网盘页与播放器的选集列表共用一份（见 dev.pikseek.ui.sort.FolderSorts）。
 */
interface SortMemory {
    /** [folderId] 该怎么排。根目录是空串。 */
    fun orderFor(folderId: String): PikoFileSortOrder

    /** 用户在 [folderId] 里改成了 [order]。 */
    fun remember(folderId: String, order: PikoFileSortOrder)
}
