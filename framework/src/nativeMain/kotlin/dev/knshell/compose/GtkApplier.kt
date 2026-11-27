@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.AbstractApplier
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import dev.knshell.core.setBoxChildMax
import dev.knshell.core.setBoxChildRatio
import dev.knshell.core.setBoxChildWeight
import kn.gtk.GtkWidget
import kn.gtk.g_object_ref
import kn.gtk.g_object_unref
import kn.gtk.gtk_widget_get_layout_manager
import kn.gtk.gtk_widget_get_parent

/**
 * A single node of the widget tree that mirrors the Compose node tree.
 *
 * Every node owns a real GTK widget (`widget`). Structural changes coming from the
 * Compose runtime are translated into `attachChild` / `detachChild` calls, which
 * subclasses implement with `gtk_box_append` / `gtk_box_remove`.
 */
open class GtkNode(val widget: CPointer<GtkWidget>) {
    /** Stable id the inspector addresses this node by; assigned at construction (main thread only). */
    internal val id: Int = GtkNodeIds.next()

    internal val children = mutableListOf<GtkNode>()

    /** What the inspector lists below this node: the children, plus content shown on a surface of its own. */
    internal open val inspectableChildren: List<GtkNode> get() = children

    /** Per-widget style provider used by modifier-based styling. */
    internal val inlineStyle = InlineStyle(widget)

    /**
     * The [Modifier] currently applied to this node.
     *
     * A container reads it to decide about its child: `weight` and the `maxWidth()`/`maxHeight()`
     * ceiling are per-child properties of the layout manager (`KnBoxLayout`), so [GtkBoxNode]
     * pushes `child.modifier.mainAxisWeight()` and `child.modifier.maxSizeCaps()` whenever the
     * child is attached or recomposed. Default is `Modifier` (no elements), which
     * keeps the whole chain "absent means default" — the same rule that makes an empty chain
     * clear `hexpand` (plan L9).
     */
    internal var modifier: Modifier = Modifier
        private set

    internal open fun applyModifier(modifier: Modifier) {
        this.modifier = modifier
        applyModifiers(widget, modifier, inlineStyle)
        // Compose applies a node's chain without asking its parent, and `weight`/`maxWidth()`
        // live in the parent: a chain that dropped them has to clear them there too (L9).
        pushChildLayout()
    }

    /**
     * Pushes the per-child layout properties [modifier] asks for — `weight` and the max-size
     * ceiling — to the layout manager that owns this node's size.
     *
     * Neither lives on the widget: GTK has no child properties for a managed box, so they are
     * `KnBoxLayout` child records — `setBoxChildWeight(parent, child, weight)` and
     * `setBoxChildMax(parent, child, maxWidth, maxHeight)`. Every node type pushes its own, not
     * just a node that happens to be a box: `Row { Text(Modifier.weight(1f)) }` has a Text node as
     * the weighted child, and a container that only pushed what its own children could see would
     * quietly drop the weight of anything that is not itself a box.
     *
     * Both are pushed even when absent — `weight = 0` and `cap = -1` clear whatever the previous
     * chain left behind. A parent with no layout manager (a plain `GtkBox`, `Box`, `Stack`) has
     * nowhere to put them, which is exactly where the cap keeps its old, narrower meaning: only
     * an explicit request is clamped. Called on attach and on every recomposition of this chain.
     */
    internal open fun pushChildLayout() {
        val parent = gtk_widget_get_parent(widget)?.asContainerHandle() ?: return
        val child = widget.rawValue.toLong().toULong()
        setBoxChildWeight(parent, child, modifier.mainAxisWeight())
        val (maxWidth, maxHeight) = modifier.maxSizeCaps()
        setBoxChildMax(parent, child, maxWidth, maxHeight)
        // Same channel for the shape: `Modifier.aspectRatio()` is a per-child record of the
        // container's manager (`main = cross * ratio`), and 0 means "no ratio" — so a chain that
        // dropped the modifier clears it instead of pinning the child to an old shape (L9).
        setBoxChildRatio(parent, child, modifier.aspectRatioValue().toDouble())
    }

    /** This widget's own handle if it is a container a layout manager sizes, otherwise null. */
    internal fun CPointer<GtkWidget>.asContainerHandle(): ULong? =
        if (gtk_widget_get_layout_manager(this) == null) null
        else this.rawValue.toLong().toULong()

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

    /**
     * Stops any in-flight animation and drops everything that points at the widget from outside;
     * called before the widget leaves the tree.
     */
    internal open fun cancelAnimations() {
        inlineStyle.input.release()
        inlineStyle.semantics.release()
        children.forEach { it.cancelAnimations() }
    }

    /**
     * `Applier.move`: [to] counts positions as they are *before* the move, so moving forward lands
     * [count] places earlier than [to] once the moved children are taken out (`A B C D` with
     * `move(0, 2, 1)` gives `B A C D`).
     */
    open fun moveChild(from: Int, to: Int, count: Int) {
        val moved = mutableListOf<GtkNode>()
        repeat(count) {
            if (from < children.size) moved.add(children.removeAt(from))
        }
        val destination = if (from > to) to else to - count
        children.addAll(destination.coerceIn(0, children.size), moved)
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
        // Detaching drops the container's reference, usually the only one: without our own, GTK
        // destroys the widget and what gets attached again is a dead pointer.
        children.forEach { g_object_ref(it.widget) }
        children.forEach { detachChild(it.widget) }
        children.forEach { attachChild(it.widget) }
        children.forEach { g_object_unref(it.widget) }
    }
}

/** Source of [GtkNode.id]: a plain counter, so ids are only ever created on the GTK main thread. */
private object GtkNodeIds {
    private var last = 0

    fun next(): Int = ++last
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

/**
 * The Compose [AbstractApplier] for the GTK node tree.
 *
 * Maps the runtime's structural operations onto real GTK calls: create → [GtkNode]
 * construction in the composable factory, insert/detach → `attachChildAt` / `detachChild`,
 * remove → `dispose`, move → `gtk_box_insert_child_after` with the target index (GTK only
 * appends, so a plain append would reverse conditional siblings).
 *
 * Public only because `:icons:core` builds its own node tree through it
 * (`ComposeNode<GtkNode, GtkApplier>`); a shell author never names it.
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
