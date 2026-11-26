package dev.knshell.atlas

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.Render
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.dsl.group

enum class Size { S, M, L }

/** Render block for tests; it is never invoked. */
val noopRender: Render = { _ -> }

/** Sample group with every arg kind, a plain story, and two variants (one axis, two axes). */
fun sampleGroup(): StoryGroup = group("Button") {
    val text by string("Save")
    val enabled by boolean(true)
    val size by enum(Size.S)
    val n by int(16, 0..64)
    val f by float(0.5f, 0f..1f)
    render { _ -> }
    story("Primary")
    variants("Sizes") { axis(size) }
    variants("Matrix") { axis(size); axis(enabled) }
}

@Suppress("UNCHECKED_CAST")
fun <A : Arg<*>> StoryGroup.arg(name: String): A = args.single { it.name == name } as A

fun StoryGroup.variantsNamed(name: String): Variants =
    entries.filterIsInstance<Variants>().single { it.name == name }
