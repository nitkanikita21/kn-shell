package dev.knshell.app.theme

fun interface WallpaperSource {
    suspend fun query(): String?
}