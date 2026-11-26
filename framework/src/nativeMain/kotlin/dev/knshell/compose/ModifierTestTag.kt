// ---------------------------------------------------------------------------
// `Modifier.testTag` — a name that addresses a widget from outside the composition (the inspector
// looks nodes up by it). It has no visual effect; the inspector reads it from the node's chain.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/**
 * Names the widget so the inspector can address it by [name] (`Modifier.testTag("send")`).
 *
 * Tags are not checked for uniqueness here: a lookup by a tag shared by several widgets fails,
 * so keep them unique within one window.
 */
fun Modifier.testTag(name: String): Modifier = with(TestTagElement(name))

/** The last `testTag` the chain names, or `null` when it names none. */
internal fun Modifier.testTagName(): String? =
    elements.filterIsInstance<TestTagElement>().lastOrNull()?.name
