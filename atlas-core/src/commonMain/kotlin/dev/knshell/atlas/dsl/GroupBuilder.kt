package dev.knshell.atlas.dsl

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.Render
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import kotlin.enums.enumEntries
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty


fun group(title: String, build: GroupBuilder.() -> Unit): StoryGroup =
    GroupBuilder(title).apply(build).build()

@AtlasDsl
class GroupBuilder internal constructor(private val title: String) {
    private val args = mutableListOf<Arg<*>>()
    private val pending = mutableListOf<() -> StoryEntry>()   // збираємо відкладено
    private var defaultRender: Render? = null

    // --- оголошення аргументів: val size by enum(ButtonSize.Medium) ---
    private fun <T, A : Arg<T>> declare(make: (String) -> A) =
        PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, A>> { _, prop ->
            val arg = make(prop.name)
            check(args.none { it.name == arg.name }) { "[$title] duplicate arg '${arg.name}'" }
            args += arg
            ReadOnlyProperty { _, _ -> arg }
        }

    fun string(default: String) = declare { Arg.Text(it, default) }
    fun boolean(default: Boolean) = declare { Arg.Flag(it, default) }
    fun <E> choice(options: List<E>, default: E) = declare { Arg.Choice(it, options, default) }
    inline fun <reified E : Enum<E>> enum(default: E) = choice(enumEntries<E>(), default)

    // --- рендер ---
    fun render(block: Render) { defaultRender = block }

    fun story(name: String, render: Render? = null) {
        pending += { Story(name, render ?: needDefault(name)) }
    }

    fun variants(name: String, render: Render? = null, build: VariantsBuilder.() -> Unit) {
        val vb = VariantsBuilder().apply(build)
        pending += {
            vb.axes.forEach { check(it.arg in args) { "[$title/$name] axis '${it.arg.name}' is not a group arg" } }
            Variants(name, vb.combos(), render ?: needDefault(name))
        }
    }

    private fun needDefault(entry: String): Render =
        defaultRender ?: error("[$title/$entry] no render: pass one or call render { } in the group")

    internal fun build() = StoryGroup(title, args.toList(), pending.map { it() })
}