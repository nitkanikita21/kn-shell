@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.LongVar
import platform.posix.localtime
import platform.posix.time

private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Wall clock: returns ("10 Sun 14", ":", "03") so the colon can be animated separately. */
fun currentClockParts(): Triple<String, String, String> = memScoped {
    val now = alloc<LongVar>()
    time(now.ptr)
    val tm = localtime(now.ptr)?.pointed ?: return@memScoped Triple("--", ":", "--")
    val day = tm.tm_mday.toString().padStart(2, '0')
    val weekday = WEEKDAYS[tm.tm_wday.coerceIn(0, 7)]
    val hours = tm.tm_hour.toString().padStart(2, '0')
    val minutes = tm.tm_min.toString().padStart(2, '0')
    Triple("$day $weekday $hours", ":", minutes)
}

/** Wall-clock time as HH:MM (local time). */
fun currentTimeHm(): String = memScoped {
    val now = alloc<LongVar>()
    time(now.ptr)
    val tm = localtime(now.ptr)?.pointed ?: return@memScoped "--:--"
    "${tm.tm_hour.toString().padStart(2, '0')}:${tm.tm_min.toString().padStart(2, '0')}"
}
