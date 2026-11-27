package dev.knshell.atlas.dsl

import dev.knshell.atlas.core.entry.DEFAULT_STATE_TARGET
import dev.knshell.atlas.play.Play
import dev.knshell.inspect.InteractionState
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Render
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import kotlin.enums.enumEntries
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty


fun group(title: String, category: String? = null, build: GroupBuilder.() -> Unit): StoryGroup =
    GroupBuilder(title, category).apply(build).build()

@AtlasDsl
class GroupBuilder internal constructor(private val title: String, private val category: String?) {
    private var docs: String? = null

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
    fun int(default: Int, range: IntRange) = declare {
        require(default in range) { "[$title] '$it' default $default is outside $range" }
        Arg.IntNum(it, default, range)
    }
    fun float(default: Float, range: ClosedFloatingPointRange<Float>) = declare {
        require(default in range) { "[$title] '$it' default $default is outside $range" }
        Arg.FloatNum(it, default, range)
    }

    // --- рендер ---
    fun render(block: Render) { defaultRender = block }

    /** What the component is for, shown beside its stories. */
    fun docs(text: String) { docs = text.trimIndent().trim() }

    /** A story with its own render, passed as a value. */
    fun story(name: String, render: Render) = story(name, render = render, play = null)

    /**
     * A story. [states] adds golden pictures with those interaction states forced on the widget tagged
     * [stateTarget]; [play] drives the story in the golden run and states what must be true.
     */
    fun story(
        name: String,
        states: List<InteractionState> = emptyList(),
        stateTarget: String = DEFAULT_STATE_TARGET,
        play: Play? = null,
        render: Render? = null,
    ) {
        pending += { Story(name, render ?: needDefault(name), states, stateTarget, play) }
    }

    fun variants(
        name: String,
        render: Render? = null,
        view: MatrixView = MatrixView.Compose,
        states: List<InteractionState> = emptyList(),
        stateTarget: String = DEFAULT_STATE_TARGET,
        play: Play? = null,
        build: VariantsBuilder.() -> Unit,
    ) {
        val vb = VariantsBuilder().apply(build)
        pending += {
            vb.axes.forEach { check(it.arg in args) { "[$title/$name] axis '${it.arg.name}' is not a group arg" } }
            require(view.supports(vb.axes.size)) {
                "[$title/$name] the ${view.title} view needs exactly 2 axes, the matrix has ${vb.axes.size}"
            }
            Variants(name, vb.combos(), vb.axes.map { it.arg }, view, render ?: needDefault(name), states, stateTarget, play)
        }
    }

    private fun needDefault(entry: String): Render =
        defaultRender ?: error("[$title/$entry] no render: pass one or call render { } in the group")

    internal fun build(): StoryGroup {
        val entries = pending.map { it() }
        entries.groupBy { it.name.lowercase() }.values.forEach { dups ->
            check(dups.size == 1) { "[$title] duplicate story '${dups[0].name}' (names are case-insensitive)" }
        }
        return StoryGroup(title, args.toList(), entries, category, docs)
    }
}
