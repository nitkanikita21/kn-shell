package dev.knshell.app.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.mp.KoinPlatform

/**
 * Composition local carrying the running Koin container down the compose tree.
 *
 * This is the single DI seam: instead of injecting services through a hand-rolled
 * service holder, composables resolve them straight from this [Koin] via
 * `LocalKoin.current.get<XService>()` (or `inject`). No aggregate wrapper class
 * is needed — Koin already owns and scopes every singleton declared in [appModule].
 */
val LocalKoin = compositionLocalOf<Koin> {
    error("No Koin container provided — wrap your tree in ProvideKoin.")
}

/**
 * Starts the Koin container once (idempotent — [KoinPlatform.getKoin] returns the
 * already-running instance on subsequent calls) and provides it to [content].
 *
 * Example:
 * ```kotlin
 * ProvideKoin {
 *     val windows by LocalKoin.current.inject<WindowsService>()
 *     // ...
 * }
 * ```
 */
@Composable
fun ProvideKoin(content: @Composable () -> Unit) {
    val koin = remember { KoinPlatform.getKoin() }
    CompositionLocalProvider(LocalKoin provides koin) {
        content()
    }
}

