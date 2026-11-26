package dev.knshell.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs [block] on the GTK thread, outside any composition: it starts once the main loop turns, and it
 * keeps running if the composition fails. For code that drives a window from the outside, such as a
 * run over many states that reads the widgets after each one.
 */
fun launchOnUiThread(block: suspend CoroutineScope.() -> Unit): Job =
    CoroutineScope(SupervisorJob() + GLibDispatcher()).launch(block = block)
