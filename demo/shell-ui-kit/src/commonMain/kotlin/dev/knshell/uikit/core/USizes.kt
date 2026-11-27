package dev.knshell.uikit.core

/** The measurements of a control of one [USize], in pixels. */
data class USizeMetrics(
    /** Height of a control, and width of a square one. */
    val height: Int,
    val fontSize: Int,
    val paddingX: Int,
    val icon: Int,
    /** Between an icon and its text. */
    val gap: Int,
)

val USize.metrics: USizeMetrics
    get() = when (this) {
        USize.Xs -> USizeMetrics(height = 24, fontSize = 11, paddingX = 8, icon = 12, gap = 4)
        USize.Sm -> USizeMetrics(height = 28, fontSize = 12, paddingX = 10, icon = 14, gap = 6)
        USize.Md -> USizeMetrics(height = 34, fontSize = 13, paddingX = 12, icon = 16, gap = 6)
        USize.Lg -> USizeMetrics(height = 40, fontSize = 14, paddingX = 16, icon = 18, gap = 8)
        USize.Xl -> USizeMetrics(height = 46, fontSize = 16, paddingX = 20, icon = 20, gap = 8)
    }
