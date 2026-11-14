package dev.knshell.services

/**
 * Entry point to the reactive shell services — the framework-style API.
 *
 * Every service is created and started lazily on first access, so merely reading
 * `Services.media.state` subscribes to the Rust backend. Callbacks stay alive for the
 * process lifetime.
 *
 * ```kotlin
 * val media by Services.media.state.collectAsState()
 * val bars by Services.spectrum.state.collectAsState()
 * val workspaces by Services.workspaces.state.collectAsState()
 *
 * Services.media.togglePlayPause()
 * Services.audio.setVolume(45)
 * Services.workspaces.focus("2")
 * ```
 */
object Services {
    val windows: WindowsService by lazy { WindowsService().also { it.start() } }
    val volume: AudioService by lazy { AudioService().also { it.start() } }
    val media: MediaService by lazy { MediaService().also { it.start() } }
    val workspaces: WorkspacesService by lazy { WorkspacesService().also { it.start() } }
    val system: SystemService by lazy { SystemService().also { it.start() } }
    val spectrum: SpectrumService by lazy { SpectrumService().also { it.start() } }
    val battery: BatteryService by lazy { BatteryService().also { it.start() } }
    val hyprland: HyprlandService by lazy { HyprlandService().also { it.start() } }
    val network: NetworkService by lazy { NetworkService().also { it.start() } }
    val notifications: NotificationsService by lazy { NotificationsService().also { it.start() } }

    /** Starts every service; individual access already starts the ones you touch. */
    fun startAll() {
        windows
        volume
        media
        workspaces
        system
        spectrum
        battery
        hyprland
        network
        notifications
    }
}
