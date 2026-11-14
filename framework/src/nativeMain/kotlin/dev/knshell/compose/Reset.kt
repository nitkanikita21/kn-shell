@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kn.gtk.GdkDisplay
import kn.gtk.GtkCssProvider
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_style_context_add_provider_for_display

/**
 * The "plain widget" reset.
 *
 * Every widget created by the Kotlin DSL carries [PLAIN_CLASS]. The stylesheet below is
 * scoped to that class and strips everything the GTK theme (Adwaita) paints by default:
 * backgrounds, borders, shadows, outlines, paddings, margins, minimum sizes, text
 * shadows, transitions and colour/font overrides. What remains is an unstyled box that
 * only paints what the developer asks for through the [Modifier] DSL.
 *
 * The provider is registered at [PLAIN_PRIORITY] (650 — above the application stylesheet
 * at 600 so the reset does not depend on provider order) while per-widget [InlineStyle]
 * providers live at 700 and therefore always win: an explicit `Modifier.background(...)`
 * still overrides the reset.
 */
const val PLAIN_CLASS: String = "kn-plain"

/** Priority of the reset provider. [InlineStyle] uses 700 and therefore wins. */
internal const val PLAIN_PRIORITY: UInt = 650u

/**
 * Class-scoped reset rules.
 *
 * Everything is scoped to `.kn-plain` (never to bare element names) so that:
 *  - it only ever affects DSL widgets, and
 *  - its class-level specificity beats the element rules of the application stylesheet
 *    (`button { ... }`, `label { ... }`, `scale { ... }`) installed at the same priority.
 *
 * Because GTK themes style interactive states *and* pseudo-classes with the same or
 * higher specificity than a single class, the button/scale rules repeat the reset for
 * `:hover`, `:active`, `:checked`, `:focus`, `:focus-visible`, `:disabled` and
 * `:backdrop`, plus the internal scale sub-nodes (`trough`, `highlight`, `slider`).
 */
const val PLAIN_CSS: String = """
/* --- base: every DSL widget ------------------------------------------------ */
.kn-plain {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    outline-color: transparent;
    outline-style: none;
    outline-width: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
    color: inherit;
    font-family: inherit;
    font-size: inherit;
    font-style: inherit;
    font-weight: inherit;
}

/* --- buttons: never let the theme paint any state --------------------------- */
button.kn-plain,
button.kn-plain:hover,
button.kn-plain:hover:focus,
button.kn-plain:active,
button.kn-plain:active:hover,
button.kn-plain:checked,
button.kn-plain:checked:hover,
button.kn-plain:focus,
button.kn-plain:focus-visible,
button.kn-plain:focus-within,
button.kn-plain:disabled,
button.kn-plain:disabled:hover,
button.kn-plain:backdrop,
button.kn-plain:backdrop:hover,
button.kn-plain:drop(active) {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    outline-color: transparent;
    outline-style: none;
    outline-width: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
    color: inherit;
    font-family: inherit;
    font-size: inherit;
    font-style: inherit;
    font-weight: inherit;
}

/* --- scales (sliders) ------------------------------------------------------- */
scale.kn-plain,
scale.kn-plain:hover,
scale.kn-plain:focus,
scale.kn-plain:focus-visible,
scale.kn-plain:disabled,
scale.kn-plain:backdrop {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    outline-color: transparent;
    outline-style: none;
    outline-width: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
    color: inherit;
}

scale.kn-plain trough,
scale.kn-plain trough:hover,
scale.kn-plain:hover trough,
scale.kn-plain:focus trough,
scale.kn-plain:disabled trough {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    outline-style: none;
    outline-width: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
}

scale.kn-plain highlight,
scale.kn-plain highlight:hover,
scale.kn-plain:hover highlight,
scale.kn-plain:disabled highlight {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
}

scale.kn-plain slider,
scale.kn-plain slider:hover,
scale.kn-plain slider:active,
scale.kn-plain:hover slider,
scale.kn-plain:focus slider,
scale.kn-plain:disabled slider {
    background-color: transparent;
    background-image: none;
    border-color: transparent;
    border-style: none;
    border-width: 0;
    border-radius: 0;
    box-shadow: none;
    outline-color: transparent;
    outline-style: none;
    outline-width: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
    min-height: 0;
    text-shadow: none;
    transition: none;
}
"""

/**
 * Holds the single reset provider.
 *
 * Top-level state in Kotlin/Native is a GC root, so the provider created here is never
 * collected while GTK still references it.
 */
private object PlainReset {
    /** Whether the provider has already been registered for a display. */
    var installed: Boolean = false

    /** The provider, kept alive for the lifetime of the process. */
    var provider: CPointer<GtkCssProvider>? = null
}

/**
 * Installs [PLAIN_CSS] on [display] once (idempotent), at [PLAIN_PRIORITY].
 *
 * Must be called after `gtk_init()` — the display is only available then. Safe to call
 * from every entry point: only the first call creates and registers the provider.
 */
internal fun installPlainReset(display: CPointer<GdkDisplay>) {
    if (PlainReset.installed) return

    val provider = gtk_css_provider_new() ?: run {
        println("[shell-ui] kn-plain: could not create the reset CSS provider")
        return
    }
    gtk_css_provider_load_from_string(provider, PLAIN_CSS)
    // Keep the provider reachable for the rest of the process: GTK does not take a
    // strong reference that Kotlin/Native's GC can see.
    PlainReset.provider = provider
    PlainReset.installed = true

    gtk_style_context_add_provider_for_display(display, provider.reinterpret(), PLAIN_PRIORITY)
    println("[shell-ui] kn-plain reset installed (priority=$PLAIN_PRIORITY)")
}
