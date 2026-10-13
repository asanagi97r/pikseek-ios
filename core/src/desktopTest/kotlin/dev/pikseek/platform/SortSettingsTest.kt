package dev.pikseek.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** 默认排序、单个文件夹记住的排序、记忆开关、重设默认时清掉记住的，以及重启后读回来。 */
class SortSettingsTest {
    private class MemoryStore : SettingsStore {
        var saved: Map<String, String> = emptyMap()
        override fun load(): Map<String, String> = saved
        override fun save(values: Map<String, String>) {
            saved = values
        }
    }

    @Test
    fun `folders follow the default until changed, and keep their own after restart`() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        assertEquals("TIME_DESC", settings.sortFor("a"), "什么都没设时按最新")
        settings.setFolderSort("a", "NAME_ASC")
        settings.setFolderSort("", "SIZE_DESC")
        assertEquals("NAME_ASC", settings.sortFor("a"))
        assertEquals("SIZE_DESC", settings.sortFor(""), "根目录也记")
        assertEquals("TIME_DESC", settings.sortFor("b"), "别的文件夹还是默认")

        val reopened = AppSettings(store)
        assertEquals("NAME_ASC", reopened.sortFor("a"), "重启后还记得")
        assertEquals("SIZE_DESC", reopened.sortFor(""))
    }

    @Test
    fun `setting the default clears what folders remembered`() {
        val settings = AppSettings(MemoryStore())
        settings.setFolderSort("a", "NAME_ASC")
        val before = settings.sortVersion.value
        settings.setDefaultSort("SIZE_ASC")
        assertEquals("SIZE_ASC", settings.sortFor("a"), "重设默认之后全体按新默认")
        assertEquals(before + 1, settings.sortVersion.value, "开着的列表要知道该重排")
        settings.setFolderSort("a", "TIME_ASC")
        assertEquals("TIME_ASC", settings.sortFor("a"), "之后再改的照样记")
    }

    @Test
    fun `with memory off every folder uses the default and nothing is recorded`() {
        val settings = AppSettings(MemoryStore())
        settings.setFolderSort("a", "NAME_ASC")
        settings.setRememberSort(false)
        assertEquals("TIME_DESC", settings.sortFor("a"))
        settings.setFolderSort("b", "NAME_DESC")
        settings.setRememberSort(true)
        assertEquals("TIME_DESC", settings.sortFor("b"), "关着时改的不记")
        assertEquals("NAME_ASC", settings.sortFor("a"), "打开后原来记住的还在")
    }

    @Test
    fun `changing back to the default forgets the folder`() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        settings.setFolderSort("a", "NAME_ASC")
        settings.setFolderSort("a", "TIME_DESC")
        assertEquals("", store.saved["sort.folders"], "和默认一样就不必记，以后改默认它跟着走")
    }
}
