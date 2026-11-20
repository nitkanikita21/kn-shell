// Entry point to the reactive services: one lazy, auto-started singleton per backend domain.

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
    /** Toplevel windows reported by the compositor. */
    val windows: WindowsService by lazy { WindowsService().also { it.start() } }
    /** Default sink volume. */
    val volume: AudioService by lazy { AudioService().also { it.start() } }
    /** Active MPRIS player and transport control. */
    val media: MediaService by lazy { MediaService().also { it.start() } }
    /** Hyprland workspaces. */
    val workspaces: WorkspacesService by lazy { WorkspacesService().also { it.start() } }
    /** CPU / RAM statistics. */
    val system: SystemService by lazy { SystemService().also { it.start() } }
    /** CAVA audio spectrum bins. */
    val spectrum: SpectrumService by lazy { SpectrumService().also { it.start() } }
    /** Battery charge and status. */
    val battery: BatteryService by lazy { BatteryService().also { it.start() } }
    /** Hyprland focus events: active window, current submap. */
    val hyprland: HyprlandService by lazy { HyprlandService().also { it.start() } }
    /** Network throughput and connection state. */
    val network: NetworkService by lazy { NetworkService().also { it.start() } }
    /** Notification history and do-not-disturb. */
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
