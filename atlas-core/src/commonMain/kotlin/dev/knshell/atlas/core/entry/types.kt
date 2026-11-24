package dev.knshell.atlas.core.entry

import androidx.compose.runtime.Composable
import dev.knshell.atlas.core.arg.ArgValues

typealias Render = @Composable (args: ArgValues) -> Unit