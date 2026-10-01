package dev.piko.ui.platform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// 界面要用的几种文本格式。原先各处直接用 java.time 与 String.format，iOS 上没有，统一收在这里。

/** 保留 [decimals] 位小数，四舍五入：`1.25.fixed(1)` 是 `1.3`。 */
internal fun Double.fixed(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = floor(abs(this) * factor + 0.5).toLong()
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    return "$sign${scaled / factor}.${(scaled % factor).toString().padStart(decimals, '0')}"
}

/** 两位数，不足补 0。 */
internal fun pad2(value: Long): String = value.toString().padStart(2, '0')

internal fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** 日期与时刻的写法。时刻一律换到本机时区再写。 */
internal object Dates {
    /** 解析 RFC 3339 的时刻，例如 `2026-10-01T08:30:00.000+08:00`。格式不对时抛异常。 */
    fun parse(rfc3339: String): Instant = Instant.parse(rfc3339)

    fun local(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime = instant.toLocalDateTime(zone)

    fun now(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime = Clock.System.now().toLocalDateTime(zone)

    fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = now(zone).date

    /** `08:05` */
    fun hourMinute(time: LocalDateTime): String = "${pad2(time.hour)}:${pad2(time.minute)}"

    /** `10 月 1 日` */
    fun monthDay(date: LocalDate): String = "${date.month.ordinal + 1} 月 ${date.day} 日"

    /** `2026 年 10 月 1 日` */
    fun yearMonthDay(date: LocalDate): String = "${date.year} 年 ${monthDay(date)}"

    /** `20261001-083005`，用在导出文件的名字里。 */
    fun fileStamp(time: LocalDateTime = now()): String =
        "${time.year.toString().padStart(4, '0')}${pad2(time.month.ordinal + 1)}${pad2(time.day)}-" +
            "${pad2(time.hour)}${pad2(time.minute)}${pad2(time.second)}"
}

private val monotonicStart = TimeSource.Monotonic.markNow()

/** 只增不减的毫秒数，起点任意。量间隔用它，不受改系统时间的影响。 */
internal fun monotonicMillis(): Long = monotonicStart.elapsedNow().inWholeMilliseconds
