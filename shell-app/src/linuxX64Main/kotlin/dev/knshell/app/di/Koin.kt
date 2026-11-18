package dev.knshell.app.di

import dev.knshell.app.infrastructure.infrastructureModule
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.theme.ThemeServiceImpl
import dev.knshell.app.theme.themeModule
import dev.knshell.services.AudioService
import dev.knshell.services.BatteryService
import dev.knshell.services.HyprlandService
import dev.knshell.services.MediaService
import dev.knshell.services.NetworkService
import dev.knshell.services.NotificationsService
import dev.knshell.services.SpectrumService
import dev.knshell.services.SystemService
import dev.knshell.services.WindowsService
import dev.knshell.services.WorkspacesService
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * The application's Koin container.
 *
 * One singleton per long-lived service (same lifecycle the old `object Services`
 * locator gave). [ThemeService] is bound to its [ThemeServiceImpl] so the UI can
 * depend on the interface. Everything is a plain `single { }` — no constructor
 * injection is used because most framework services take no parameters; the
 * container is handed to the compose tree via [ProvideKoin].
 *
 * NOTE: platform-specific glue (Kotlin/Native has no `expect`/Android context
 * here) is unnecessary for a single linuxX64 target, so the whole module lives
 * in linuxX64Main rather than commonMain.
 */
val appModule = module {
    single { Json { ignoreUnknownKeys = true } }
    includes(
        infrastructureModule,
        themeModule
    )
}

/**
 * Starts Koin exactly once. Call this from [main] before the composition is
 * entered, so the container can be provided through [ProvideKoin].
 */
fun initKoin() {
    startKoin {
        modules(appModule)
    }
}
