@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.primitives

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.knshell.compose.GtkApplier
import dev.knshell.compose.GtkNode
import dev.knshell.compose.Modifier
import dev.knshell.compose.plain
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kn.gtk.GdkPaintable
import kn.gtk.GdkPixbuf
import kn.gtk.GdkTexture
import kn.gtk.GtkContentFit
import kn.gtk.GtkPicture
import kn.gtk.gdk_pixbuf_new_from_file_at_scale
import kn.gtk.gdk_texture_new_for_pixbuf
import kn.gtk.gtk_picture_new
import kn.gtk.gtk_picture_set_can_shrink
import kn.gtk.gtk_picture_set_content_fit
import kn.gtk.gtk_picture_set_paintable
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_size_request
import kn.gtk.gtk_widget_set_valign
import kn.gtk.GTK_ALIGN_START

/**
 * Image from a file path, scaled to [width]×[height] pixels at load time
 * (`gdk_pixbuf_new_from_file_at_scale` → `GdkTexture` → `GtkPicture`).
 *
 * Scaling at load time is deliberate: `GtkPicture` reports the image's natural size, and a
 * size request is only a minimum, so an unscaled icon would stretch its row (a 512px logo in
 * a 28px slot). Here the picture's natural size *is* the requested size.
 *
 * ```kotlin
 * Image("/tmp/cover.png", width = 28, height = 28, modifier = Modifier.radius(6))
 * ```
 */
@Composable
fun Image(
    path: String,
    modifier: Modifier = Modifier,
    width: Int = 24,
    height: Int = width,
) {
    ComposeNode<GtkPictureNode, GtkApplier>(
        factory = { GtkPictureNode(path, width, height).plain() },
        update = {
            set(path) { value -> load(value, width, height) }
            set(width) { value -> resize(value, height) }
            set(height) { value -> resize(width, value) }
            set(modifier) { value -> applyModifier(value) }
        },
    )
}

internal class GtkPictureNode(path: String, width: Int, height: Int) :
    GtkNode(gtk_picture_new()!!) {

    @Suppress("unused")
    private var pixbuf: CPointer<GdkPixbuf>? = null

    @Suppress("unused")
    private var texture: CPointer<GdkTexture>? = null

    private var requestedWidth = width
    private var requestedHeight = height

    init {
        val picture = widget.reinterpret<GtkPicture>()
        gtk_picture_set_content_fit(picture, GtkContentFit.GTK_CONTENT_FIT_CONTAIN)
        gtk_picture_set_can_shrink(picture, 1)
        pinSize()
        load(path, width, height)
    }

    /**
     * A widget's size request is only a minimum, and `GtkPicture` fills its cross axis by
     * default, so a 28x28 icon would still be drawn at the row height. START alignment plus
     * the request makes the allocation exactly the requested size.
     */
    private fun pinSize() {
        gtk_widget_set_size_request(widget, requestedWidth, requestedHeight)
        gtk_widget_set_halign(widget, GTK_ALIGN_START)
        gtk_widget_set_valign(widget, GTK_ALIGN_START)
    }

    fun resize(width: Int, height: Int) {
        if (width == requestedWidth && height == requestedHeight) return
        requestedWidth = width
        requestedHeight = height
        pinSize()
        load(path = lastPath, width = width, height = height)
    }

    private var lastPath = path

    fun load(path: String, width: Int, height: Int) {
        lastPath = path
        if (path.isBlank()) return
        val loaded = gdk_pixbuf_new_from_file_at_scale(
            path,
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            1,
            null,
        ) ?: return
        pixbuf = loaded
        val created = gdk_texture_new_for_pixbuf(loaded) ?: return
        texture = created
        gtk_picture_set_paintable(widget.reinterpret<GtkPicture>(), created.reinterpret<GdkPaintable>())
    }
}
