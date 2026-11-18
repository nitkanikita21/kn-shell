package dev.knshell.app.theme

import org.koin.dsl.module

val themeModule = module {
    single<WallpaperSource> { AwwwWallpaperSource }
    single<ThemeService> { ThemeServiceImpl(get(), get()) }

}