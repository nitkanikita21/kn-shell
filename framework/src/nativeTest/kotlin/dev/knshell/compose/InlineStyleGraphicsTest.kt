@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_init_check
import kn.gtk.gtk_scale_new_with_range
import kn.gtk.gtk_widget_measure
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `InlineStyle` against a real GTK style cascade, with the plain reset installed as in a running
 * window: rules for the widget's own node and rules for its CSS sub-nodes (`scale trough`) take
 * different routes into GTK, and both must win over the reset.
 *
 * Requires a display connection, like [LayoutProbeTest].
 */
class InlineStyleGraphicsTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "InlineStyleGraphicsTest needs a display connection" }
        installPlainReset(gdk_display_get_default()!!)
    }

    @Test
    fun `a sub-node rule reaches the sub-node and beats the plain reset`() {
        val (scale, style) = plainScale()

        style.setRules(listOf(" trough" to "min-height: 90px;"))

        assertEquals(90, height(scale))
    }

    @Test
    fun `a modifier chain styles slider sub-nodes`() {
        val (scale, style) = plainScale()

        applyModifiers(scale, Modifier.slider(troughHeight = 70), style)

        assertEquals(70, height(scale))
    }

    @Test
    fun `an own-node rule still applies`() {
        val (scale, style) = plainScale()

        style.setRules(listOf("" to "min-height: 80px;"))

        assertEquals(80, height(scale))
    }

    @Test
    fun `a dropped sub-node rule stops applying`() {
        val (scale, style) = plainScale()
        val unstyled = height(scale)
        style.setRules(listOf(" trough" to "min-height: 90px;"))

        style.setRules(listOf("" to "color: red;"))

        assertEquals(unstyled, height(scale))
    }

    @Test
    fun `a sub-node rule stays on its own widget`() {
        val (_, style) = plainScale()
        val (other, _) = plainScale()
        val unstyled = height(other)

        style.setRules(listOf(" trough" to "min-height: 90px;"))

        assertEquals(unstyled, height(other))
    }

    @Test
    fun `destroying the widget releases its display provider`() {
        val parent = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        val scale = newScale().also { gtk_box_append(parent.reinterpret<GtkBox>(), it) }
        val before = InlineStyle.sharedProviderCount
        InlineStyle(scale).setRules(listOf(" trough" to "min-height: 90px;"))
        assertEquals(before + 1, InlineStyle.sharedProviderCount)

        // The box holds the only reference, so removing the child finalizes it.
        gtk_box_remove(parent.reinterpret<GtkBox>(), scale)

        assertEquals(before, InlineStyle.sharedProviderCount)
    }

    private fun newScale(): CPointer<GtkWidget> =
        gtk_scale_new_with_range(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0.0, 10.0, 1.0)!!

    private fun plainScale(): Pair<CPointer<GtkWidget>, InlineStyle> {
        val parent = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        val node = GtkSliderNode(0f, 10f, 1f).plain()
        gtk_box_append(parent.reinterpret<GtkBox>(), node.widget)
        return node.widget to node.inlineStyle
    }

    private fun height(widget: CPointer<GtkWidget>): Int = memScoped {
        val out = allocArray<IntVar>(4)
        gtk_widget_measure(widget, GtkOrientation.GTK_ORIENTATION_VERTICAL, -1, out, out + 1, out + 2, out + 3)
        out[0]
    }
}
