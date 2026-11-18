package dev.knshell.app.ui.helpers

import androidx.compose.runtime.Composable
import dev.knshell.app.di.LocalKoin

@Composable
inline fun <reified T : Any> injectDi() = LocalKoin.current.inject<T>()