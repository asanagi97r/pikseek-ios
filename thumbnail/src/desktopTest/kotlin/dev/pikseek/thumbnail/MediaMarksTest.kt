package dev.pikseek.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaMarksTest {
    private val gcid = "ab".repeat(20)
    private val marks = MediaMarks(
        gcid = gcid.uppercase(),
        durationMs = 1_440_000,
        scenes = listOf(600_000, 900_000),
        scenesDone = true,
        intro = MediaMarks.Span(30_000, 120_000),
        outro = MediaMarks.Span(1_320_000, 1_410_000),
        episodeDone = true,
    )

    @Test
    fun roundTripsAndOldOrForeignFilesAreRejected() {
        assertEquals(marks, MediaMarks.decode(marks.encode()))
        assertNull(MediaMarks.decode("不是 JSON".encodeToByteArray()))
        assertNull(MediaMarks.decode("""{"version":99,"gcid":"$gcid","durationMs":1000}""".encodeToByteArray()), "以后的版本读不懂就不读")
        // 认不得的字段照常读（以后加了东西，老版本不该因此读不了）
        val newer = MediaMarks.decode("""{"version":1,"gcid":"$gcid","durationMs":5000,"scenes":[4000,1000,9000],"somethingNew":true}""".encodeToByteArray())
        assertEquals(listOf(1000L, 4000L), newer?.scenes, "排好序、去掉越界的")
        assertEquals(gcid.uppercase(), newer?.gcid)
    }

    @Test
    fun fileNameCarriesTheGcid() {
        assertEquals("${gcid.uppercase()}.psmarks", marks.fileName)
        assertEquals(gcid.uppercase(), MediaMarks.gcidOf(marks.fileName))
        assertNull(MediaMarks.gcidOf("${gcid}_M_60of60.pspreview"))
        assertNull(MediaMarks.gcidOf("short.psmarks"))
        assertEquals(gcid.uppercase(), MediaMarks.gcidOf("${gcid}(1).psmarks"), "网盘给重名上传另起的名字照样认")
    }

    @Test
    fun snapPointsIncludeSceneAndIntroOutroEdges() {
        assertEquals(listOf(30_000L, 120_000L, 600_000L, 900_000L, 1_320_000L, 1_410_000L), marks.snapPoints)
        // 片头从 0 开始时，0 不是分点
        assertEquals(listOf(90_000L), MediaMarks(gcid = gcid, durationMs = 600_000, intro = MediaMarks.Span(0, 90_000)).snapPoints)
        assertEquals(600_000L, marks.nearestPoint(605_000, 10_000))
        assertNull(marks.nearestPoint(650_000, 10_000))
    }

    @Test
    fun segmentsAndStepping() {
        assertEquals(3, marks.segmentCount)
        assertEquals(0, marks.segmentAt(10_000))
        assertEquals(1, marks.segmentAt(600_000))
        assertEquals(2, marks.segmentAt(1_000_000))
        assertEquals(600_000L, marks.nextPoint(130_000))
        // 刚跳到分点上，再按「下一段」不会原地不动
        assertEquals(900_000L, marks.nextPoint(600_500))
        // 在一段开头不远处按「上一段」，退到再前一个
        assertEquals(120_000L, marks.previousPoint(601_000))
        assertNull(marks.previousPoint(20_000))
    }

    @Test
    fun manualEditsAreMarkedAndKeepTheRest() {
        val added = marks.withScene(300_000)
        assertEquals(listOf(300_000L, 600_000L, 900_000L), added.scenes)
        assertTrue(added.scenesEdited)
        assertFalse(added.episodeEdited)
        assertEquals(added, added.withScene(301_000), "离已有的太近不加")
        val removed = added.withoutScene(598_000, 5_000)
        assertEquals(listOf(300_000L, 900_000L), removed.scenes)
        assertEquals(removed, removed.withoutScene(700_000, 5_000), "附近没有就不动")

        val introEnd = marks.withIntroEnd(100_000)
        assertEquals(MediaMarks.Span(30_000, 100_000), introEnd.intro, "原来的片头开头留着")
        assertTrue(introEnd.episodeEdited)
        assertEquals(MediaMarks.Span(0, 100_000), marks.copy(intro = null).withIntroEnd(100_000).intro)
        assertEquals(MediaMarks.Span(1_300_000, 1_410_000), marks.withOutroStart(1_300_000).outro)
        assertEquals(MediaMarks.Span(1_300_000, 1_440_000), marks.copy(outro = null).withOutroStart(1_300_000).outro)
        val cleared = marks.withoutIntroOutro()
        assertNull(cleared.intro)
        assertNull(cleared.outro)
        assertTrue(cleared.episodeDone && cleared.episodeEdited)
    }
}
