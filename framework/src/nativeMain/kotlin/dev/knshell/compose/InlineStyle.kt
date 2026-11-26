// ---------------------------------------------------------------------------
// `InlineStyle` — the per-widget CSS provider and generated class every
// `Modifier` chain styles through, plus the per-widget record of which layout
// fields the chain has written (so a dropped modifier can reset them). This is
// the only place a widget gets its private stylesheet, so nothing here touches
// the global theme.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GObject
import kn.gtk.GtkCssProvider
import kn.gtk.GtkWidget
import kn.gtk.g_object_get_data
import kn.gtk.g_object_set_data
import kn.gtk.g_object_unref
import kn.gtk.g_object_weak_ref
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_style_context_add_provider_for_display
import kn.gtk.gtk_style_context_remove_provider_for_display
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_get_style_context

private object InlineStyleId {
    var next = 0
}

/** 700 = GTK_STYLE_PROVIDER_PRIORITY_APPLICATION + 100: above the app stylesheet and the reset. */
private const val INLINE_PRIORITY: UInt = 700u

/** A selector with a combinator (`" trough"`, `" popover > contents"`) targets another CSS node. */
private fun targetsSubNode(selector: String): Boolean = selector.any { it.isWhitespace() || it == '>' || it == '~' || it == '+' }

/**
 * Applies CSS declarations to a single widget through private style providers and a
 * generated class. This is what makes modifier-based styling possible: each widget gets
 * exactly the CSS its modifiers describe, without touching a global stylesheet.
 *
 * GTK 4 takes two routes here. A provider added to a widget's style context only styles that
 * widget's own CSS node, so it carries the widget rules and its states (`:hover`). Sub-node
 * rules (`scale trough`) are only reachable from a display-wide provider; it stays private to
 * this widget through the generated class and is removed when the widget is finalized.
 * Changing a display provider restyles the whole display, hence it exists only for widgets
 * that actually have sub-node rules.
 */
internal class InlineStyle(private val widget: CPointer<GtkWidget>) {

    /** Click/hover/cursor wiring (see Interaction.kt). */
    internal val interaction = InteractionState(widget)

    private var provider: CPointer<GtkCssProvider>? = null
    private var sharedProvider: CPointer<GtkCssProvider>? = null
    private var className: String? = null
    private var lastCss: String? = null
    private var lastSharedCss: String = ""


    fun setRules(rules: List<Pair<String, String>>) {
        if (rules.isEmpty() && className == null) return

        val cls = ensureClass()
        val (shared, own) = rules.partition { targetsSubNode(it.first) }
        setOwnCss(cssFor(cls, own))
        setSharedCss(cssFor(cls, shared))
    }

    private fun cssFor(cls: String, rules: List<Pair<String, String>>): String {
        val base = rules.filter { it.first.isEmpty() }.joinToString(separator = " ") { it.second }
        return buildString {
            if (base.isNotBlank()) append(".$cls { $base }\n")
            rules.filter { it.first.isNotEmpty() }
                .forEach { (selector, declarations) -> append(".$cls$selector { $declarations }\n") }
        }
    }

    private fun setOwnCss(css: String) {
        if (css == lastCss) return
        lastCss = css

        val cssProvider = ensureProvider() ?: return
        gtk_css_provider_load_from_string(cssProvider, css)
    }

    private fun setSharedCss(css: String) {
        if (css == lastSharedCss) return
        lastSharedCss = css

        val cssProvider = ensureSharedProvider() ?: return
        gtk_css_provider_load_from_string(cssProvider, css)
    }

    private fun ensureClass(): String {
        className?.let { return it }
        val cls = "knm-${InlineStyleId.next++}"
        gtk_widget_add_css_class(widget, cls)
        className = cls
        return cls
    }

    private fun ensureProvider(): CPointer<GtkCssProvider>? {
        provider?.let { return it }
        val cssProvider = gtk_css_provider_new() ?: return null
        val context = gtk_widget_get_style_context(widget)
        if (context != null) {
            gtk_style_context_add_provider(context, cssProvider.reinterpret(), INLINE_PRIORITY)
        }
        provider = cssProvider
        return cssProvider
    }

    private fun ensureSharedProvider(): CPointer<GtkCssProvider>? {
        sharedProvider?.let { return it }
        val display = gdk_display_get_default() ?: return null
        val cssProvider = gtk_css_provider_new() ?: return null
        gtk_style_context_add_provider_for_display(display, cssProvider.reinterpret(), INLINE_PRIORITY)
        // The display would keep the provider for the life of the process: release it with the
        // widget. The provider itself is the callback data, so nothing here retains the widget.
        g_object_weak_ref(
            widget.reinterpret<GObject>(),
            staticCFunction { data: COpaquePointer?, _: CPointer<GObject>? -> releaseSharedProvider(data) },
            cssProvider,
        )
        sharedProviderCount++
        sharedProvider = cssProvider
        return cssProvider
    }

    internal companion object {
        /** Display-wide providers currently installed; lets tests see that none leak. */
        var sharedProviderCount: Int = 0
    }
}

private fun releaseSharedProvider(data: COpaquePointer?) {
    val provider = data ?: return
    gdk_display_get_default()?.let { display ->
        gtk_style_context_remove_provider_for_display(display, provider.reinterpret())
    }
    g_object_unref(provider)
    InlineStyle.sharedProviderCount--
}

// Which layout fields a `Modifier` chain has written on a widget, kept as GLib data on the widget
// itself (plan L9). GObject data needs a pointer, and the only thing ever read back is whether the
// key is present, so the value is the widget's own pointer: `g_object_set_data()` takes no destroy
// callback and never frees what it stores, and writing `null` removes the key again. The record
// therefore dies with the widget — no registry, no leak, and a widget that only ever got its
// alignment from outside the modifier pipeline is never touched.

internal const val HEXPAND_KEY = "kn-modifier-hexpand"
internal const val VEXPAND_KEY = "kn-modifier-vexpand"
internal const val HALIGN_KEY = "kn-modifier-halign"
internal const val VALIGN_KEY = "kn-modifier-valign"
internal const val OPACITY_KEY = "kn-modifier-opacity"
internal const val SIZE_KEY = "kn-modifier-size"

/** Whether a [Modifier] chain has written this layout field on [widget] before. */
internal fun fieldWritten(widget: CPointer<GtkWidget>, key: String): Boolean =
    g_object_get_data(widget.reinterpret<GObject>(), key) != null

/** Claims [key] for the modifier chain, or hands the field back when [written] is false. */
internal fun setFieldWritten(widget: CPointer<GtkWidget>, key: String, written: Boolean) {
    val value = if (written) widget.reinterpret<GObject>() else null
    g_object_set_data(widget.reinterpret<GObject>(), key, value)
}

