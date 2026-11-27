package dev.knshell.atlas.runner

import androidx.compose.runtime.Composable

/**
 * One way to show the catalog's stories, supplied by the catalog itself: typically its theme in one
 * mode. [wrap] surrounds every story with it. The atlas adds no styling of its own: it offers a
 * switch between the environments and records goldens for each.
 */
class AtlasEnvironment(
    val name: String,
    val wrap: @Composable (story: @Composable () -> Unit) -> Unit,
)
