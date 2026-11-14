package dev.knshell.ui

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Float formatting for Kotlin/Native.
 *
 * `String.format` / `java.util.Formatter` are JVM-only, so these helpers implement the
 * common cases directly and are locale-independent.
 */

/** Rounds to [decimals] digits after the dot: `3.14159f.format(2) == "3.14"`. */
fun Float.format(decimals: Int = 0): String {
    if (decimals <= 0) return roundToLong().toString()

    val factor = 10.0.pow(decimals).toLong()
    val scaled = (this * factor).roundToLong()
    val negative = scaled < 0
    val magnitude = abs(scaled)
    val whole = magnitude / factor
    val fraction = (magnitude % factor).toString().padStart(decimals, '0')

    return (if (negative) "-" else "") + whole.toString() + "." + fraction
}

/** Nearest integer: `42.6f.whole() == 43`. */
fun Float.whole(): Int = roundToLong().toInt()

/** Percentage string: `0.815f.percent(1) == "81.5%"`. */
fun Float.percent(decimals: Int = 0): String = format(decimals) + "%"

/** Largest integer not greater than the value: `42.9f.floorInt() == 42`. */
fun Float.floorInt(): Int = kotlin.math.floor(this).toInt()

/** Smallest integer not less than the value: `42.1f.ceilInt() == 43`. */
fun Float.ceilInt(): Int = kotlin.math.ceil(this).toInt()

/**
 * Human-readable throughput: `formatBytesPerSec(1_500_000) == "1.4 MiB/s"`.
 * Binary units (1024) are used deliberately — this is what network tools report.
 */
fun formatBytesPerSec(bytesPerSecond: Long): String {
    if (bytesPerSecond < 0) return "0 B/s"
    val units = listOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
    var value = bytesPerSecond.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${bytesPerSecond} ${units[0]}" else value.toFloat().format(1) + " " + units[index]
}
