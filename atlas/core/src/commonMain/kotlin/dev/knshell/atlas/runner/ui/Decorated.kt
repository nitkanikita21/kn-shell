package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.compose.Modifier
import dev.knshell.compose.OverlayHost
import dev.knshell.compose.StyleReset

/** The environment the preview shows stories in; `null` when the catalog supplies none. */
internal val LocalAtlasEnvironment = compositionLocalOf<AtlasEnvironment?> { null }

/**
 * Everything that surrounds a story, the same in the preview and in the golden run: styles of the
 * surroundings stop here, then the catalog's environment is applied.
 */
@Composable
internal fun Decorated(environment: AtlasEnvironment?, modifier: Modifier, story: @Composable () -> Unit) {
    StyleReset(modifier) {
        // Room for the story's dialogs and toasts.
        OverlayHost {
            if (environment == null) {
                story()
            } else {
                key(environment.name) { environment.wrap(story) }
            }
        }
    }
}
