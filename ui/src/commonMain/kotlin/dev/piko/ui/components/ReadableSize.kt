package dev.piko.ui.components

import dev.piko.ui.platform.fixed
import kotlin.math.log10
import kotlin.math.pow

fun Long.toReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (log10(this.toDouble()) / log10(1024.0)).toInt()
    digitGroups = digitGroups.coerceIn(0, units.lastIndex)
    return "${(this / 1024.0.pow(digitGroups.toDouble())).fixed(1)} ${units[digitGroups]}"
}
