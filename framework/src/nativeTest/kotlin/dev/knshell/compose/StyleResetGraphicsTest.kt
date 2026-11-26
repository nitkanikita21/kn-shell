@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.GdkRGBA
import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.g_free
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_init_check
import kn.gtk.gtk_label_new
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_get_color
import kn.gtk.gtk_widget_get_pango_context
import kn.gtk.pango_context_get_font_description
import kn.gtk.pango_font_description_to_string
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** What a label inside [resetStyles] takes from its ancestors. Requires a display connection. */
class StyleResetGraphicsTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "StyleResetGraphicsTest needs a display connection" }
        installPlainReset(gdk_display_get_default()!!)
    }

    private val loud = Modifier
        .fontFamily("JetBrainsMono Nerd Font")
        .fontSize(31)
        .fontWeight(900)
        .textColor(Color.hex(0xFF0000), transitionMs = 0)

    @Test
    fun `without a reset a label takes the ancestor's styles`() {
        val bare = label(parent = box())
        val styled = label(parent = box(loud))

        assertNotEquals(font(bare), font(styled))
        assertEquals(Triple(255, 0, 0), color(styled))
    }

    @Test
    fun `inside a reset a label looks as if nothing surrounded it`() {
        val bare = label(parent = box())
        val isolated = label(parent = box(Modifier.resetStyles(), parent = box(loud)))

        assertEquals(font(bare), font(isolated))
        assertEquals(Triple(0xE8, 0xE8, 0xEE), color(isolated))
    }

    @Test
    fun `other modifiers of the container survive the reset wherever it stands`() {
        val container = box(Modifier.textColor(Color.hex(0x00FF00), transitionMs = 0).resetStyles(), box(loud))

        assertEquals(Triple(0, 255, 0), color(label(parent = container)))
    }

    private fun box(modifier: Modifier = Modifier, parent: CPointer<GtkWidget>? = null): CPointer<GtkWidget> {
        val widget = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        gtk_widget_add_css_class(widget, PLAIN_CLASS)
        parent?.let { gtk_box_append(it.reinterpret<GtkBox>(), widget) }
        applyModifiers(widget, modifier, InlineStyle(widget))
        return widget
    }

    private fun label(parent: CPointer<GtkWidget>): CPointer<GtkWidget> {
        val widget = gtk_label_new("atlas")!!
        gtk_widget_add_css_class(widget, PLAIN_CLASS)
        gtk_box_append(parent.reinterpret<GtkBox>(), widget)
        return widget
    }

    /** Family, size, weight and style of the text, as Pango describes them. */
    private fun font(widget: CPointer<GtkWidget>): String {
        val description = pango_context_get_font_description(gtk_widget_get_pango_context(widget))
        val text = pango_font_description_to_string(description)!!
        return text.toKString().also { g_free(text) }
    }

    private fun color(widget: CPointer<GtkWidget>): Triple<Int, Int, Int> = memScoped {
        val rgba = alloc<GdkRGBA>()
        gtk_widget_get_color(widget, rgba.ptr)
        Triple((rgba.red * 255).roundToInt(), (rgba.green * 255).roundToInt(), (rgba.blue * 255).roundToInt())
    }
}
