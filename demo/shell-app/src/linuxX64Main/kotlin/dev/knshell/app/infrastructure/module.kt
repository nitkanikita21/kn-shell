package dev.knshell.app.infrastructure

import dev.knshell.services.AudioService
import dev.knshell.services.BatteryService
import dev.knshell.services.HyprlandService
import dev.knshell.services.MediaService
import dev.knshell.services.NetworkService
import dev.knshell.services.NotificationsService
import dev.knshell.services.ShellService
import dev.knshell.services.SpectrumService
import dev.knshell.services.SystemService
import dev.knshell.services.WindowsService
import dev.knshell.services.WorkspacesService
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Every service keeps its concrete registration (`get<WindowsService>()` still works) and is
 * additionally bound to [ShellService], so the whole collection is reachable via
 * `getAll<ShellService>()` or `get<Set<ShellService>>()`.
 */
val infrastructureModule = module {
    single { WindowsService() } bind ShellService::class
    single { AudioService() } bind ShellService::class
    single { MediaService() } bind ShellService::class
    single { WorkspacesService() } bind ShellService::class
    single { SystemService() } bind ShellService::class
    single { SpectrumService() } bind ShellService::class
    single { BatteryService() } bind ShellService::class
    single { HyprlandService() } bind ShellService::class
    single { NetworkService() } bind ShellService::class
    single { NotificationsService() } bind ShellService::class

    // The same singletons collected once, so the whole set can be injected as a value.
    single<Set<ShellService>> { getAll<ShellService>().toSet() }
}
