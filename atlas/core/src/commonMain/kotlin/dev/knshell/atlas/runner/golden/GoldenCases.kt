package dev.knshell.atlas.runner.golden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.ArgSnapshot
import dev.knshell.atlas.core.arg.CellArgs
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.atlas.runner.ui.captionFor

/**
 * One run of one story: with its default args, or one combination of a matrix, in one environment.
 * A case gives one picture, and more for the story's states and `play` snapshots.
 */
class GoldenCase(
    val group: StoryGroup,
    val entry: StoryEntry,
    /** The axis values of a matrix cell; `null` for a story. */
    val combo: ArgSnapshot?,
    /** What surrounds the story; `null` when the catalog supplies no environments. */
    val environment: AtlasEnvironment? = null,
) {
    /** `Group/Entry`, as stories are named everywhere else. */
    val story: String = "${group.title}/${entry.name}"

    private val caption: String? = if (entry is Variants && combo != null) captionFor(entry, combo) else null

    /** The story or cell without the environment: `Group/Entry` or `Group/Entry/caption`. */
    val subject: String = if (caption == null) story else "$story/$caption"

    /** [subject], after `Environment/` when there are environments. */
    val id: String = listOfNotNull(environment?.name, subject).joinToString("/")

    /** Where the golden lives, relative to the golden directory. */
    val path: String = listOfNotNull(environment?.name, group.title, entry.name, caption)
        .joinToString("/", transform = ::safeName) + ".png"

    /** The story as it first appears: every arg at its default, no state left from another case. */
    @Composable
    fun Content() {
        val live = remember { LiveArgs(group.args) }
        entry.render(if (combo == null) live else CellArgs(combo, live))
    }
}

/**
 * [name] as one file name: characters that file systems or paths give a meaning become `_`, and so
 * does `@`, which separates a state or snapshot from the case.
 */
internal fun safeName(name: String): String {
    val cleaned = name.map { if (it in "/\\:*?\"<>|@" || it.code < 32) '_' else it }.joinToString("").trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
}

/**
 * Every case of the catalog, once per environment. [filter] narrows it to one story (`Group/Entry`,
 * all its cells), one cell (`Group/Entry/caption`) or one case id, ignoring case. Throws
 * [IllegalArgumentException] when the filter matches nothing or two cases would share a file.
 */
fun goldenCases(
    groups: List<StoryGroup>,
    filter: String? = null,
    environments: List<AtlasEnvironment> = emptyList(),
): List<GoldenCase> {
    val surroundings: List<AtlasEnvironment?> = environments.ifEmpty { listOf(null) }
    val all = surroundings.flatMap { environment ->
        groups.flatMap { group ->
            group.entries.flatMap { entry ->
                if (entry is Variants) {
                    entry.combos.map { GoldenCase(group, entry, it, environment) }
                } else {
                    listOf(GoldenCase(group, entry, null, environment))
                }
            }
        }
    }
    all.groupBy { it.path }.values.firstOrNull { it.size > 1 }?.let { same ->
        throw IllegalArgumentException(
            "these cases would share the file ${same.first().path}: ${same.joinToString { it.id }}",
        )
    }
    if (filter == null) return all
    val chosen = all.filter { case ->
        listOf(case.story, case.subject, case.id).any { it.equals(filter, ignoreCase = true) }
    }
    require(chosen.isNotEmpty()) {
        "no story matches \"$filter\"; known: ${all.map { it.story }.distinct().joinToString()}"
    }
    return chosen
}
