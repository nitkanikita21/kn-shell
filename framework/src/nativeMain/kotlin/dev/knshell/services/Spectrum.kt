// `bucketLevels` — reduces a CAVA spectrum to N averaged bars.

package dev.knshell.services

/**
 * Reduces a CAVA spectrum (e.g. 16 bins, each 0..100) to [count] bars by averaging groups.
 *
 * Each bucket covers `[i*source/count, (i+1)*source/count)`, so every bar count samples the
 * whole spectrum — 6 bars and 8 bars show the same shape with different detail, instead of
 * 8 bars showing "more values" from the same first-N slice.
 */
fun bucketLevels(values: List<Int>, count: Int): DoubleArray {
    require(count > 0) { "count must be positive, was $count" }
    val result = DoubleArray(count)
    val source = values.size
    if (source == 0) return result
    for (index in 0 until count) {
        val start = index * source / count
        val end = (((index + 1) * source / count).coerceAtLeast(start + 1)).coerceAtMost(source)
        var sum = 0.0
        var samples = 0
        var i = start
        while (i < end) {
            sum += values[i].coerceIn(0, 100)
            samples++
            i++
        }
        result[index] = if (samples == 0) 0.0 else sum / samples / 100.0
    }
    return result
}
