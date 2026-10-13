package dev.piko.shared.media.player

import dev.piko.shared.data.PikoFileSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** 选集按文件夹的排序重排：分区先后不动，同组的版本跟着走。 */
class PlaylistSortTest {
    private fun entry(id: String, section: String, time: String, size: Long, group: String = id, primary: Boolean = true) =
        PlaylistEntry(fileId = id, name = "$id.mp4", label = id, size = size, sectionKey = section, groupKey = group, primary = primary, createdTime = time)

    // 正片三集（第 2 集有两个版本），再一个 SP
    private val built = listOf(
        entry("e1", "main", "2026-01-03", 300),
        entry("e2", "main", "2026-01-01", 900),
        entry("e2-720", "main", "2026-01-05", 100, group = "e2", primary = false),
        entry("e3", "main", "2026-01-02", 500),
        entry("sp", "sp", "2026-01-09", 50),
    )

    private fun ids(order: PikoFileSortOrder) = sortPlaylist(built, order).map { it.fileId }

    @Test
    fun `name ascending keeps the episode order`() = assertEquals(built.map { it.fileId }, ids(PikoFileSortOrder.NAME_ASC))

    @Test
    fun `name descending reverses episodes inside each section`() =
        assertEquals(listOf("e3", "e2", "e2-720", "e1", "sp"), ids(PikoFileSortOrder.NAME_DESC))

    @Test
    fun `time uses the main version of each group`() {
        assertEquals(listOf("e1", "e3", "e2", "e2-720", "sp"), ids(PikoFileSortOrder.TIME_DESC))
        assertEquals(listOf("e2", "e2-720", "e3", "e1", "sp"), ids(PikoFileSortOrder.TIME_ASC))
    }

    @Test
    fun `size sorts groups by their largest version`() =
        assertEquals(listOf("e2", "e2-720", "e3", "e1", "sp"), ids(PikoFileSortOrder.SIZE_DESC))
}
