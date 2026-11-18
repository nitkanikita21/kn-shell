@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.icons

import dev.knshell.compose.Color
import kotlinx.cinterop.*
import kn.gtk.GtkWidget
import kn.gtk.cairo_restore
import kn.gtk.cairo_save
import kn.gtk.cairo_t
import kn.gtk.g_object_ref_sink
import kn.gtk.g_object_unref
import kn.rsvg.RsvgHandle
import kn.rsvg.RsvgRectangle
import kn.rsvg.rsvg_handle_new_from_data
import kn.rsvg.rsvg_handle_render_document

/**
 * librsvg-backed renderer with a two-level LRU: tinted *document* strings
 * (regex work) and compiled `RsvgHandle`s (XML parse). Both levels share the
 * render-identity key `pack:id:color:width`, so one icon at two colors tints
 * its markup twice and parses each color exactly once — never per frame.
 *
 * Everything runs on the GTK main thread (draw func + composition), so the
 * caches need no locking. Kotlin/Native's LinkedHashMap has no access-order
 * mode, hence the manual move-to-back LRU below.
 */
internal object RsvgCache {

    /** Entries kept per level; one icon costs ~2 KB of string + one parsed handle. */
    var maxEntries: Int = 256

    private val documents = Lru<String>()

    // Eviction must release the librsvg object immediately — otherwise the LRU
    // "forgets" the pointer but the handle leaks.
    private val handles = Lru<CPointer<Nothing>?>(evictHook = { h -> h?.let { g_object_unref(it) } })
    private val warned = mutableSetOf<String>()

    /**
     * Move-to-back LRU. [evictHook] receives every displaced value (nullable [V]
     * includes cached "failed" markers) so owning resources can be released.
     */
    private class Lru<V : Any?>(private val evictHook: ((V?) -> Unit)? = null) {
        private val map = LinkedHashMap<String, V>()

        operator fun get(key: String): V? = map.remove(key)?.also { map.put(key, it) }

        operator fun set(key: String, value: V) {
            map.remove(key)
            map.put(key, value)
            while (map.size > maxEntries) {
                val eldest = map.keys.firstOrNull() ?: break
                val dropped = map.remove(eldest)
                evictHook?.invoke(dropped)
            }
        }

        operator fun contains(key: String): Boolean = map.containsKey(key)
        fun values(): Iterable<V> = map.values
        fun size(): Int = map.size
        fun clear() = map.clear()
    }

    private fun key(ref: IconRef, colorHex: String?, strokeWidth: Double?): String =
        "${ref.pack.name}:${ref.id}:${colorHex ?: "native"}:${strokeWidth ?: "pack"}"

    /** Live (non-failed) compiled handles — test/inspection hook, not a hot path. */
    fun liveHandleCount(): Int = handles.values().count { it != null }

    /** Document-cache entries currently held (resolved ids, incl. failed ones). */
    fun documentCount(): Int = documents.size()

    /** Drop everything and unref the orphaned handles. Test / hot-reload hook. */
    fun clear() {
        handles.values().forEach { h -> h?.let { g_object_unref(it) } }
        handles.clear()
        documents.clear()
        warned.clear()
    }

    /**
     * Compiled handle for this exact render identity, or `null` when nothing can
     * be painted: the pack has no markup for [ref], or the document failed to
     * parse. Both cases warn once per identity and are cached as resolved, so a
     * missing icon costs one string compare per frame, never an exception.
     *
     * Eviction unrefs the dropped handle (`onEvicted` below), so a 256-entry
     * cap bounds live librsvg state no matter how many icons a session touches.
     */
    fun handleFor(ref: IconRef, colorHex: String?, strokeWidth: Double?): CPointer<Nothing>? {
        val k = key(ref, colorHex, strokeWidth)
        if (k in documents) return handles[k]

        val inner = ref.source
        if (inner == null) {
            warnOnce("missing", "${ref.pack.name}:${ref.id}")
            documents[k] = "" // resolved; renders an empty slot
            return null
        }

        val doc = buildTintedDocument(inner, ref.style, colorHex, strokeWidth)
        if (doc == null) {
            warnOnce("empty", k)
            documents[k] = ""
            return null
        }
        documents[k] = doc

        val bytes = doc.encodeToByteArray()
        val handle = memScoped {
            val buf = allocArray<UByteVar>(bytes.size)
            for (i in bytes.indices) buf[i] = bytes[i].toUByte()
            // The ctor hands out a floating ref; ref_sink makes it cache-owned.
            rsvg_handle_new_from_data(buf, bytes.size.toULong(), null)
                ?.let { h -> g_object_ref_sink(h); h.reinterpret<Nothing>() }
        }
        if (handle == null) warnOnce("parse", k)
        handles[k] = handle
        return handle
    }

    private fun warnOnce(kind: String, key: String) {
        if (warned.add("$kind:$key")) {
            println("[icons] $kind SVG lookup failed for '$key' — painting empty slot")
        }
    }

    /**
     * Paint [ref] into a cairo context of a GTK drawing area, as a `side`×`side`
     * square centered inside the `w`×`h` allocation the widget actually got.
     *
     * The offset is computed here instead of delegated to widget alignment:
     * `GtkBox` distributes its leftover main-axis space into the child's
     * allocation and ignores `valign` there, so a 22px icon inside a 44px box
     * receives a 22×44 cell — drawing at (0,0) pins the artwork to its top-left
     * corner, which is what made `Modifier.align(Align.Center)` look broken.
     * Centering by viewport is correct for every container (Row/Box/Overlay,
     * `Align.Fill`, stretched or squeezed) and costs one subtraction.
     *
     * [size] is the icon's requested box; the artwork never renders larger than
     * it, and shrinks when the parent squeezes the widget below that. Called
     * from the draw func, so [color] is the *current* tween value, not the
     * animation target.
     *
     * HiDPI needs no manual scaling: GtkDrawingArea hands the draw func a cairo
     * context already transformed by the device scale, so rasterizing the
     * document at exactly the logical widget size renders at device resolution.
     */
    fun render(
        widget: CPointer<GtkWidget>,
        ref: IconRef,
        cr: CPointer<cairo_t>,
        w: Int,
        h: Int,
        size: Int,
        color: Color?,
        strokeWidth: Double?,
    ) {
        val hex = color?.let { colorToHex(it) }
        val handle = handleFor(ref, hex, strokeWidth) ?: return
        val (x, y, side) = iconViewport(w, h, size)
        memScoped {
            val viewport = alloc<RsvgRectangle> {
                this.x = x
                this.y = y
                width = side
                height = side
            }
            cairo_save(cr)
            try {
                rsvg_handle_render_document(handle.reinterpret<RsvgHandle>(), cr, viewport.ptr, null)
            } finally {
                cairo_restore(cr)
            }
        }
    }
}

/**
 * Geometry of the artwork inside a draw func's `w`×`h` allocation: a square of
 * [size], centered, and clamped so a squeezed parent never crops it.
 *
 * Returned as `Triple(x, y, side)` so the rule is unit-testable without a
 * display or a cairo context.
 */
internal fun iconViewport(w: Int, h: Int, size: Int): Triple<Double, Double, Double> {
    val side = minOf(size.toDouble(), w.toDouble(), h.toDouble()).coerceAtLeast(0.0)
    return Triple((w - side) / 2.0, (h - side) / 2.0, side)
}
