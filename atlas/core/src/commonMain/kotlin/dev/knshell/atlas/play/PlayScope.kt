// ---------------------------------------------------------------------------
// `play { }`: what a user does to a story and what must be true afterwards. Runs in the golden run,
// against the story alone on the stage, with real key presses.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.play

import dev.knshell.compose.Key
import dev.knshell.inspect.InspectException
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.InteractionState

/** The steps of a story's `play` block. */
fun interface Play {
    suspend fun PlayScope.run()
}

/** A step or an expectation of a play script did not hold. */
class PlayFailure(message: String) : Exception(message)

/** What a script drives: the running window. */
interface PlayDriver {
    /** The widgets on the stage, popups included. */
    fun tree(): InspectNode

    suspend fun settle()

    fun click(id: Int)

    fun focus(id: Int)

    fun setState(id: Int, state: InteractionState, on: Boolean)

    fun setText(id: Int, text: String)

    /** `null` when the run has no keyboard (it is not under headless-compositor). */
    fun press(key: Key, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean)

    fun type(text: String)

    /** A picture of the stage, or of the widget [id] alone. */
    suspend fun snapshot(name: String, id: Int?)
}

/**
 * The scope of a `play` block. Targets are testTags. Every action waits until the UI settled, so an
 * expectation after it sees its result.
 */
class PlayScope internal constructor(private val driver: PlayDriver) {
    private var steps = 0
    private var last = "the start"

    suspend fun press(
        key: Key,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ) = step("press ${if (shift) "Shift+" else ""}${if (ctrl) "Ctrl+" else ""}${key.name}") {
        driver.press(key, shift, ctrl, alt, meta)
    }

    suspend fun type(text: String) = step("type \"$text\"") { driver.type(text) }

    suspend fun click(target: String) = step("click $target") { driver.click(find(target).id) }

    suspend fun focus(target: String) = step("focus $target") { driver.focus(find(target).id) }

    suspend fun hover(target: String) =
        step("hover $target") { driver.setState(find(target).id, InteractionState.Hover, true) }

    suspend fun setText(target: String, text: String) =
        step("set the text of $target") { driver.setText(find(target).id, text) }

    /** Waits for the UI to settle, for work a script did not start itself. */
    suspend fun settle() = driver.settle()

    /**
     * A golden picture at this point, named after the case and [name]: of the stage, or of the widget
     * tagged [target], which is the way to picture the content of a popup.
     */
    suspend fun snapshot(name: String, target: String? = null) =
        driver.snapshot(name, target?.let { find(it).id })

    /** Assertions on the widget tagged [target]. */
    fun expect(target: String): Expectation = Expectation(this, target, find(target))

    fun expectAbsent(target: String) {
        if (nodes().any { it.testTag == target }) fail("expected no $target")
    }

    internal fun fail(message: String): Nothing = throw PlayFailure("after $last: $message")

    /** The focused widgets, named by their tag or type, for failure messages. */
    internal fun focusedNodes(): String = nodes().filter { it.focused }
        .joinToString { it.testTag ?: "an untagged ${it.type}${it.name?.let { name -> " \"$name\"" } ?: ""}" }
        .ifEmpty { "nothing" }

    private suspend fun step(what: String, action: () -> Unit) {
        steps++
        last = "step $steps ($what)"
        try {
            action()
        } catch (e: InspectException) {
            fail(e.message.orEmpty())
        }
        driver.settle()
    }

    private fun nodes(): List<InspectNode> {
        fun walk(node: InspectNode): List<InspectNode> = listOf(node) + node.children.flatMap(::walk)
        return walk(driver.tree())
    }

    private fun find(target: String): InspectNode {
        val all = nodes()
        val matches = all.filter { it.testTag == target }
        return matches.singleOrNull() ?: fail(
            if (matches.isEmpty()) {
                "nothing is tagged $target; tags: ${all.mapNotNull { it.testTag }.joinToString()}"
            } else {
                "${matches.size} widgets are tagged $target"
            },
        )
    }
}

/** Assertions on one widget as it was when `expect` was called. Each returns this, to chain them. */
class Expectation internal constructor(
    private val scope: PlayScope,
    private val target: String,
    private val node: InspectNode,
) {
    private fun check(what: String, expected: Any?, actual: Any?): Expectation {
        if (expected != actual) scope.fail("$target: expected $what $expected, got $actual")
        return this
    }

    fun role(role: String) = check("role", role, node.role)

    fun name(name: String) = check("name", name, node.name)

    fun description(description: String?) = check("description", description, node.description)

    fun text(text: String) = check("text", text, node.text)

    fun focused(focused: Boolean = true): Expectation {
        if (focused != node.focused) {
            scope.fail("$target: expected focused $focused, got ${node.focused}; focus is on ${scope.focusedNodes()}")
        }
        return this
    }

    fun enabled(enabled: Boolean = true) = check("enabled", enabled, node.sensitive)

    /** An ARIA state or value, such as `state("expanded", "true")`; `null` expects it to be absent. */
    fun state(name: String, value: String?) = check(name, value, node.states[name])

    fun selected(selected: Boolean = true) = state("selected", selected.toString())

    fun expanded(expanded: Boolean = true) = state("expanded", expanded.toString())

    /** `"true"`, `"false"` or `"mixed"`. */
    fun checked(checked: String = "true") = state("checked", checked)
}
