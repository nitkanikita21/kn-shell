@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.AbstractApplier
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GtkWidget

/**
 * A single node of the widget tree that mirrors the Compose node tree.
 *
 * Every node owns a real GTK widget (`widget`). Structural changes coming from the
 * Compose runtime are translated into `attachChild` / `detachChild` calls, which
 * subclasses implement with `gtk_box_append` / `gtk_box_remove`.
 */
open class GtkNode(val widget: CPointer<GtkWidget>) {
    internal val children = mutableListOf<GtkNode>()

    open fun insertChild(index: Int, child: GtkNode) {
        val safeIndex = index.coerceIn(0, children.size)
        children.add(safeIndex, child)
        attachChild(child.widget)
    }

    open fun removeChild(index: Int, count: Int) {
        repeat(count) {
            if (index < children.size) {
                val child = children.removeAt(index)
                detachChild(child.widget)
            }
        }
    }

    open fun moveChild(from: Int, to: Int, count: Int) {
        val moved = mutableListOf<GtkNode>()
        repeat(count) {
            if (from < children.size) moved.add(children.removeAt(from))
        }
        val safeTo = to.coerceIn(0, children.size)
        children.addAll(safeTo, moved)
        rebuildOrder()
    }

    open fun clear() {
        children.forEach { detachChild(it.widget) }
        children.clear()
    }

    protected open fun attachChild(child: CPointer<GtkWidget>) {}
    protected open fun detachChild(child: CPointer<GtkWidget>) {}

    /** Re-appends children in list order (used after `move`). */
    protected open fun rebuildOrder() {
        children.forEach { detachChild(it.widget) }
        children.forEach { attachChild(it.widget) }
    }
}

/**
 * Bridges Compose's node insertion/removal protocol onto the GTK widget tree.
 *
 * Compose inserts children *before* their parent ("bottom-up" applier): this is
 * exactly what GTK needs, because a widget can only be appended once its own
 * children already form a valid tree.
 */
class GtkApplier(root: GtkNode) : AbstractApplier<GtkNode>(root) {
    override fun insertTopDown(index: Int, instance: GtkNode) {
        // Children are inserted in insertBottomUp.
    }

    override fun insertBottomUp(index: Int, instance: GtkNode) {
        current.insertChild(index, instance)
    }

    override fun remove(index: Int, count: Int) {
        current.removeChild(index, count)
    }

    override fun move(from: Int, to: Int, count: Int) {
        current.moveChild(from, to, count)
    }

    override fun onClear() {
        root.clear()
    }
}
