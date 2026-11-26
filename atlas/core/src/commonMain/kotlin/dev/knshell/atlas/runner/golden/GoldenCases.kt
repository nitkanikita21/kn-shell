package dev.knshell.atlas.runner.golden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.ArgSnapshot
import dev.knshell.atlas.core.arg.CellArgs
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.ui.captionFor

/** One picture of the catalog: a story with its default args, or one combination of a matrix. */
class GoldenCase(
    val group: StoryGroup,
    val entry: StoryEntry,
    /** The axis values of a matrix cell; `null` for a story. */
    val combo: ArgSnapshot?,
) {
    /** `Group/Entry`, as stories are named everywhere else. */
    val story: String = "${group.title}/${entry.name}"

    private val caption: String? = if (entry is Variants && combo != null) captionFor(entry, combo) else null

    /** `Group/Entry`, or `Group/Entry/caption` for a matrix cell. */
    val id: String = if (caption == null) story else "$story/$caption"

    /** Where the golden lives, relative to the golden directory. */
    val path: String = listOfNotNull(group.title, entry.name, caption).joinToString("/", transform = ::safeName) + ".png"

    /** The story as it first appears: every arg at its default, no state left from another case. */
    @Composable
    fun Content() {
        val live = remember { LiveArgs(group.args) }
        entry.render(if (combo == null) live else CellArgs(combo, live))
    }
}

/** [name] as one file name: characters that file systems or paths give a meaning become `_`. */
internal fun safeName(name: String): String {
    val cleaned = name.map { if (it in "/\\:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
}

/**
 * Every picture of the catalog. [filter] narrows it to one story (`Group/Entry`, all its cells) or
 * one cell (`Group/Entry/caption`), ignoring case. Throws [IllegalArgumentException] when the filter
 * matches nothing or two cases would share a file.
 */
fun goldenCases(groups: List<StoryGroup>, filter: String? = null): List<GoldenCase> {
    val all = groups.flatMap { group ->
        group.entries.flatMap { entry ->
            if (entry is Variants) {
                entry.combos.map { GoldenCase(group, entry, it) }
            } else {
                listOf(GoldenCase(group, entry, null))
            }
        }
    }
    all.groupBy { it.path }.values.firstOrNull { it.size > 1 }?.let { same ->
        throw IllegalArgumentException(
            "these cases would share the file ${same.first().path}: ${same.joinToString { it.id }}",
        )
    }
    if (filter == null) return all
    val chosen = all.filter { it.story.equals(filter, ignoreCase = true) || it.id.equals(filter, ignoreCase = true) }
    require(chosen.isNotEmpty()) {
        "no story matches \"$filter\"; known: ${all.map { it.story }.distinct().joinToString()}"
    }
    return chosen
}
