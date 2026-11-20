package dev.knshell.app.ui.helpers

import org.koin.mp.KoinPlatform

/**
 * Resolves a service from the process-wide Koin container started by [dev.knshell.app.di.initKoin].
 *
 * Deliberately **not** a CompositionLocal: every window and every popover mounts its own
 * `Composition` (`dev.knshell.shell.ShellRuntime.createWindow`, `mountCompose`), and a
 * CompositionLocal does not cross that boundary — DI through one would silently break the
 * moment the shell grows a bar plus separate windows. The container itself is a process
 * singleton, so composables can simply ask it.
 *
 * Usage stays delegation-friendly: `val theme by injectDi<ThemeService>()`.
 */
inline fun <reified T : Any> injectDi(): Lazy<T> = KoinPlatform.getKoin().inject()
