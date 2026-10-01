package dev.pikseek.platform

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/** 当前时刻，自 1970 年起的毫秒。各平台共用的代码里不能用 System.currentTimeMillis。 */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 把时刻写成给人看的文本。报告与安全页共用一种写法。 */
object TimeText {
    /** `2026-10-01 08:30:05 +08:00` */
    fun stamp(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val instant = Instant.fromEpochMilliseconds(millis)
        val local = instant.toLocalDateTime(zone)
        val offsetSeconds = zone.offsetAt(instant).totalSeconds
        val sign = if (offsetSeconds < 0) '-' else '+'
        val absolute = if (offsetSeconds < 0) -offsetSeconds else offsetSeconds
        return "${pad(local.year, 4)}-${pad(local.month.ordinal + 1)}-${pad(local.day)} " +
            "${pad(local.hour)}:${pad(local.minute)}:${pad(local.second)} " +
            "$sign${pad(absolute / 3600)}:${pad(absolute % 3600 / 60)}"
    }

    /** `08:30:05`，本机时区 */
    fun clock(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${pad(local.hour)}:${pad(local.minute)}:${pad(local.second)}"
    }

    private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
}
