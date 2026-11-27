// ---------------------------------------------------------------------------
// How components get their look: the design tokens and the component defaults come from the
// composition, so a change of theme recomposes what reads it and nothing else.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.textColor
import kotlin.reflect.KClass

/**
 * The defaults of one component, such as `UButtonDefaults`. Each component declares its own class
 * next to itself; [UThemeConfig] carries the ones an application overrides.
 */
interface UComponentDefaults

/** Component defaults of a theme, at most one per class. */
class UThemeConfig private constructor(private val entries: Map<KClass<out UComponentDefaults>, UComponentDefaults>) {

    /** `UThemeConfig(UButtonDefaults(variant = UVariant.Soft), UToastDefaults(…))`. */
    constructor(vararg defaults: UComponentDefaults) : this(defaults.associateBy { it::class })

    /** The overrides of class [T], if the theme has them. */
    fun <T : UComponentDefaults> find(type: KClass<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return entries[type] as T?
    }

    inline fun <reified T : UComponentDefaults> find(): T? = find(T::class)

    /** This configuration with [other] on top: a class [other] has replaces this one's. */
    operator fun plus(other: UThemeConfig): UThemeConfig = UThemeConfig(entries + other.entries)

    override fun equals(other: Any?): Boolean = other is UThemeConfig && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()
}

private class UThemeValues(val tokens: DesignTokens, val config: UThemeConfig)

// Not `static`: only what reads the theme recomposes when it changes.
private val LocalUTheme = compositionLocalOf { UThemeValues(DesignTokens.default(), UThemeConfig()) }

/** The theme of the current place. */
object UTheme {
    val tokens: DesignTokens
        @Composable get() = LocalUTheme.current.tokens

    val config: UThemeConfig
        @Composable get() = LocalUTheme.current.config
}

/**
 * Gives [content] the design [tokens] and component defaults. Nested themes inherit: what is not
 * passed stays as outside, and [config] is laid over the outer one.
 */
@Composable
fun UTheme(
    tokens: DesignTokens = UTheme.tokens,
    config: UThemeConfig = UThemeConfig(),
    content: @Composable () -> Unit,
) {
    val outer = UTheme.config
    val merged = remember(outer, config) { outer + config }
    val values = remember(tokens, merged) { UThemeValues(tokens, merged) }
    CompositionLocalProvider(LocalUTheme provides values) { content() }
}

/** Size of running text in pixels. */
private const val BODY_FONT_SIZE = 13

/**
 * An area painted as the theme's window: its background, text colour and font, which plain labels
 * inside inherit.
 */
@Composable
fun USurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = UTheme.tokens
    val ms = tokens.motion.themeMs
    Column(
        modifier = Modifier
            .background(tokens.colors.background, transitionMs = ms)
            .textColor(tokens.colors.foreground, transitionMs = ms)
            .fontFamily(tokens.typography.fontFamily)
            .fontSize(BODY_FONT_SIZE)
            .then(modifier),
    ) { content() }
}
