package dev.pikseek.thumbnail

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewPackTest {
    private val gcid = "0123456789ABCDEF0123456789ABCDEF01234567"

    @Test
    fun packRoundTripsAndRejectsDamage() {
        val files = listOf("index.json" to "{}".encodeToByteArray(), "sheet-000.webp" to ByteArray(1000) { it.toByte() })
        val bytes = PreviewPack.encode(files)
        val back = assertNotNull(PreviewPack.decode(bytes))
        assertEquals(files.map { it.first }, back.map { it.first })
        files.zip(back).forEach { (a, b) -> assertContentEquals(a.second, b.second) }
        assertNull(PreviewPack.decode(bytes.copyOf(bytes.size - 1)), "截断")
        assertNull(PreviewPack.decode(bytes + 0), "多出字节")
        assertNull(PreviewPack.decode("not a pack".encodeToByteArray()))
    }

    @Test
    fun nameCarriesDensityAndProgress() {
        val name = PreviewPackName(gcid.lowercase(), PreviewDensity.Medium, 37, 120)
        assertEquals("${gcid}_M_37of120.pspreview", name.fileName)
        val parsed = assertNotNull(PreviewPackName.parse(name.fileName))
        assertEquals(PreviewPackName(gcid, PreviewDensity.Medium, 37, 120), parsed)
        assertEquals(37f / 120, parsed.fraction)
        assertTrue(PreviewPackName.parse("${gcid}_H_240of240.pspreview")!!.isComplete)
    }

    @Test
    fun theCopyNumberTheDriveAddsToADuplicateNameIsTolerated() {
        // 网盘上传同名文件时另起一个「…(1)」的名字
        assertEquals(PreviewPackName(gcid, PreviewDensity.Medium, 60, 60), PreviewPackName.parse("${gcid}_M_60of60(1).pspreview"))
        assertEquals(PreviewPackName(gcid, PreviewDensity.Low, 3, 30), PreviewPackName.parse("${gcid}_L_3of30 (12).pspreview"))
        assertNull(PreviewPackName.parse("${gcid}_M_60of60(x).pspreview"))
    }

    @Test
    fun foreignNamesAreIgnored() {
        listOf(
            "${gcid}_M_37of120.webp",
            "${gcid}_X_1of2.pspreview",
            "${gcid.take(39)}_M_1of2.pspreview",
            "${gcid}_M_1of0.pspreview",
            "${gcid}_M_a of 2.pspreview",
            "${gcid}_M_1of2_extra.pspreview",
            "随便一个视频.mp4",
        ).forEach { assertNull(PreviewPackName.parse(it), it) }
    }
}
