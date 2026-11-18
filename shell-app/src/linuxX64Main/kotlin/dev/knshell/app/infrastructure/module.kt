package dev.knshell.app.infrastructure

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
import org.koin.dsl.module

val infrastructureModule = module {
    single { WindowsService() }
    single { AudioService() }
    single { MediaService() }
    single { WorkspacesService() }
    single { SystemService() }
    single { SpectrumService() }
    single { BatteryService() }
    single { HyprlandService() }
    single { NetworkService() }
    single { NotificationsService() }
}
