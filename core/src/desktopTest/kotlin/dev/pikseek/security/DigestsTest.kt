package dev.pikseek.security

import dev.pikseek.platform.TimeText
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/** 自己写的摘要与时间格式，逐一和 JDK 的结果对。 */
class DigestsTest {
    @Test
    fun md5AndSha256MatchTheJdkAtEveryPaddingBoundary() {
        val random = Random(20261001)
        // 55、56、63、64 是补位换块的边界
        val lengths = (0..130).toList() + listOf(1000, 4096, 65537)
        for (length in lengths) {
            val input = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("MD5").digest(input), Digests.md5(input), "MD5 of $length bytes")
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(input), Digests.sha256(input), "SHA-256 of $length bytes")
        }
    }

    @Test
    fun knownAnswers() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Digests.hex(Digests.md5(ByteArray(0))))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Digests.hex(Digests.md5("abc".encodeToByteArray())))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digests.hex(Digests.sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun timeStampMatchesTheJdkFormatter() {
        val moments = listOf(0L, 1_000L, 1_790_812_805_123L, 951_782_400_000L, 1_709_251_199_000L)
        for (zone in listOf("UTC", "Asia/Shanghai", "America/St_Johns", "Asia/Kolkata", "America/New_York")) {
            val jdk = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx").withZone(ZoneId.of(zone))
            for (millis in moments) {
                assertEquals(jdk.format(Instant.ofEpochMilli(millis)), TimeText.stamp(millis, TimeZone.of(zone)), "$zone at $millis")
            }
        }
    }
}
