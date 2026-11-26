// ---------------------------------------------------------------------------
// PNG rendering of a window area. The window is snapshotted into a render node, the node is
// rendered by the window's GSK renderer into a texture cropped to the wanted rectangle, and the
// texture is encoded. Every GObject, render node and GBytes created here is released on every path.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kn.gtk.GdkPaintable
import kn.gtk.GdkTexture
import kn.gtk.GskRenderer
import kn.gtk.GskRenderNode
import kn.gtk.GtkWidget
import kn.gtk.graphene_rect_t
import kn.gtk.g_bytes_get_data
import kn.gtk.g_bytes_get_size
import kn.gtk.g_bytes_unref
import kn.gtk.g_object_unref
import kn.gtk.gdk_paintable_snapshot
import kn.gtk.gdk_texture_save_to_png_bytes
import kn.gtk.gsk_render_node_unref
import kn.gtk.gsk_renderer_render_texture
import kn.gtk.gtk_native_get_renderer
import kn.gtk.gtk_snapshot_free_to_node
import kn.gtk.gtk_snapshot_new
import kn.gtk.gtk_widget_get_native
import kn.gtk.gtk_widget_paintable_new

/**
 * Renders the [area] of [window] (a window of [windowWidth]×[windowHeight] px, area in its
 * coordinates) and returns it as PNG bytes. Throws [InspectException] when GTK paints nothing.
 */
internal fun renderPng(
    window: CPointer<GtkWidget>,
    windowWidth: Int,
    windowHeight: Int,
    area: NodeBounds,
): ByteArray {
    val paintable = gtk_widget_paintable_new(window) ?: throw InspectException("the window has no paintable")
    val renderNode = try {
        snapshotOf(paintable, windowWidth, windowHeight)
    } finally {
        g_object_unref(paintable.reinterpret())
    }
    if (renderNode == null) throw InspectException("the window painted nothing")
    try {
        val native = gtk_widget_get_native(window) ?: throw InspectException("the window has no native surface")
        val renderer = gtk_native_get_renderer(native) ?: throw InspectException("the window has no renderer")
        val texture = renderTexture(renderer, renderNode, area)
            ?: throw InspectException("the renderer produced no texture for ${area.width}x${area.height} px")
        try {
            return encodePng(texture)
        } finally {
            g_object_unref(texture.reinterpret())
        }
    } finally {
        gsk_render_node_unref(renderNode)
    }
}

/**
 * The render node of the whole window, as GTK's paintable draws it. `null` when nothing is painted.
 * The snapshot itself is consumed by `gtk_snapshot_free_to_node`, so only the node is returned.
 */
private fun snapshotOf(paintable: CPointer<GdkPaintable>, width: Int, height: Int): CPointer<GskRenderNode>? {
    val snapshot = gtk_snapshot_new() ?: throw InspectException("could not create a GTK snapshot")
    gdk_paintable_snapshot(paintable, snapshot.reinterpret(), width.toDouble(), height.toDouble())
    return gtk_snapshot_free_to_node(snapshot)
}

/** Renders [node] restricted to [area] (the viewport); `null` when the renderer gives nothing. */
private fun renderTexture(
    renderer: CPointer<GskRenderer>,
    node: CPointer<GskRenderNode>,
    area: NodeBounds,
): CPointer<GdkTexture>? = memScoped {
    val viewport = alloc<graphene_rect_t>()
    viewport.origin.x = area.x.toFloat()
    viewport.origin.y = area.y.toFloat()
    viewport.size.width = area.width.toFloat()
    viewport.size.height = area.height.toFloat()
    gsk_renderer_render_texture(renderer, node, viewport.ptr)
}

/** Encodes [texture] as PNG and copies the bytes out of GLib. */
internal fun encodePng(texture: CPointer<GdkTexture>): ByteArray {
    val bytes = gdk_texture_save_to_png_bytes(texture) ?: throw InspectException("PNG encoding failed")
    try {
        val size = g_bytes_get_size(bytes).toInt()
        val data = g_bytes_get_data(bytes, null)?.reinterpret<ByteVar>()
            ?: throw InspectException("PNG encoding produced no data")
        if (size == 0) throw InspectException("PNG encoding produced an empty image")
        return data.readBytes(size)
    } finally {
        g_bytes_unref(bytes)
    }
}
