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

    /** Per-widget style provider used by modifier-based styling. */
    internal val inlineStyle = InlineStyle(widget)

    /**
     * The [Modifier] currently applied to this node.
     *
     * A container reads it to decide about its child: `weight` is a per-child property of the
     * layout manager (`KnBoxLayout`), so [GtkBoxNode] pushes `child.modifier.mainAxisWeight()`
     * whenever the child is attached or recomposed. Default is `Modifier` (no elements), which
     * keeps the whole chain "absent means default" — the same rule that makes an empty chain
     * clear `hexpand` (plan L9).
     */
    internal var modifier: Modifier = Modifier
        private set

    internal open fun applyModifier(modifier: Modifier) {
        this.modifier = modifier
        applyModifiers(widget, modifier, inlineStyle)
    }

    /**
     * Lets a container push a per-child layout property it reads from [modifier].
     *
     * `weight` lives in the parent's layout manager (GTK has no child properties for a managed
     * box), so a node cannot apply it to itself: [GtkBoxNode] overrides this and calls
     * `setBoxChildWeight(parent, child, weight)`. The default is a no-op for leaves and for
     * containers that do not manage weights. Called by the parent on attach and on every
     * recomposition of the child's own chain.
     */
    internal open fun pushWeight() {}
    open fun insertChild(index: Int, child: GtkNode) {
        val safeIndex = index.coerceIn(0, children.size)
        children.add(safeIndex, child)
        attachChildAt(safeIndex, child.widget)
    }

    open fun removeChild(index: Int, count: Int) {
        repeat(count) {
            if (index < children.size) {
                val child = children.removeAt(index)
                child.cancelAnimations()
                detachChild(child.widget)
            }
        }
    }

    /** Stops any in-flight animation; called before the widget leaves the tree. */
    internal open fun cancelAnimations() {
        children.forEach { it.cancelAnimations() }
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
        children.forEach { it.cancelAnimations() }
        children.forEach { detachChild(it.widget) }
        children.clear()
    }

    /**
     * Inserts [child] at [index] in the parent's child order. Containers that care about
     * order (GtkBox) override this; others keep the default append behaviour.
     *
     * Compose inserts siblings by index, so ignoring the index would misplace widgets as
     * soon as a conditional child swaps type (`if (playing) Equalizer() else Icon()`).
     */
    protected open fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        attachChild(child)
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
/**
 * Public bridge for the internal [GtkNode.applyModifier] pipeline.
 *
 * Satellite widget modules (`:icons:core` and future ones) define their own
 * GtkNode subclasses; they must route `Modifier` values through exactly the
 * same [applyModifiers] code as the built-in widgets, without re-exposing
 * [InlineStyle] as API. Compose-node factories inside those modules call this.
 */
fun GtkNode.applyModifierFromModule(modifier: Modifier) {
    applyModifier(modifier)
}

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
