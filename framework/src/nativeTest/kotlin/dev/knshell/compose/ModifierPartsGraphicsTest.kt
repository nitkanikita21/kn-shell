@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_label_new
import kn.gtk.gtk_widget_get_pango_context
import kn.gtk.pango_context_get_font_description
import kn.gtk.pango_font_description_get_family
import kn.gtk.gtk_init_check
import kn.gtk.gtk_widget_measure
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The typed part modifiers against a real GTK style cascade: each one is checked by the space it
 * takes up, so a declaration GTK does not apply shows as a missing pixel, not as a string mismatch.
 *
 * Requires a display connection, like the other `*GraphicsTest` classes.
 */
class ModifierPartsGraphicsTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "ModifierPartsGraphicsTest needs a display connection" }
        installPlainReset(gdk_display_get_default()!!)
    }

    @Test
    fun `a bottom border adds its width below the widget`() {
        val plain = measure(GtkOrientation.GTK_ORIENTATION_VERTICAL)
        val bordered = measure(
            GtkOrientation.GTK_ORIENTATION_VERTICAL,
            Modifier.border(Color.hex(0xFF0000), width = 3, sides = setOf(BorderSide.Bottom)),
        )

        assertEquals(plain + 3, bordered)
    }

    @Test
    fun `a left border adds its width beside the widget`() {
        val plain = measure(GtkOrientation.GTK_ORIENTATION_HORIZONTAL)
        val bordered = measure(
            GtkOrientation.GTK_ORIENTATION_HORIZONTAL,
            Modifier.border(Color.hex(0xFF0000), width = 2, sides = setOf(BorderSide.Left)),
        )

        assertEquals(plain + 2, bordered)
    }

    @Test
    fun `a border on all sides adds its width on both axes`() {
        val plain = measure(GtkOrientation.GTK_ORIENTATION_VERTICAL)
        val bordered = measure(GtkOrientation.GTK_ORIENTATION_VERTICAL, Modifier.border(Color.hex(0xFF0000), width = 1))

        assertEquals(plain + 2, bordered)
    }

    @Test
    fun `an unquoted family name with spaces reaches the text`() {
        val label = gtk_label_new("atlas")!!
        applyModifiers(label, Modifier.fontFamily("JetBrainsMono Nerd Font"), InlineStyle(label))

        val description = pango_context_get_font_description(gtk_widget_get_pango_context(label))
        assertEquals("JetBrainsMono Nerd Font", pango_font_description_get_family(description)?.toKString())
    }

    /** The natural size of a scale carrying [modifier], along one axis. */
    private fun measure(orientation: GtkOrientation, modifier: Modifier = Modifier): Int {
        val parent = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        val node = GtkSliderNode(0f, 10f, 1f).plain()
        gtk_box_append(parent.reinterpret<GtkBox>(), node.widget)
        applyModifiers(node.widget, modifier, node.inlineStyle)
        return size(node.widget, orientation)
    }

    private fun size(widget: CPointer<GtkWidget>, orientation: GtkOrientation): Int = memScoped {
        val out = allocArray<IntVar>(4)
        gtk_widget_measure(widget, orientation, -1, out, out + 1, out + 2, out + 3)
        out[0]
    }
}
