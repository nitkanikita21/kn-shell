// ---------------------------------------------------------------------------
// What the tree of the sidebar lists, as plain data: the catalog in its categories, narrowed by the
// words of a search.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants

class SidebarGroup(val group: StoryGroup, val entries: List<StoryEntry>)

class SidebarSection(val category: String?, val groups: List<SidebarGroup>)

/** The words of a search; a story matches when it has all of them. */
fun searchWords(query: String): List<String> = query.lowercase().split(' ', '\t', '/').filter { it.isNotEmpty() }

/** How the tree lists the entries of a group: stories, then matrices, each as the catalog declares them. */
fun sidebarOrder(entries: List<StoryEntry>): List<StoryEntry> = entries.sortedBy { it is Variants }

/**
 * The tree: categories in the order the catalog first names them, their groups and entries. A story
 * is listed when each of [words] occurs in its category, group or name, ignoring case, and [include]
 * accepts it. Groups and categories without a listed story are left out.
 */
fun sidebarSections(
    groups: List<StoryGroup>,
    words: List<String> = emptyList(),
    include: (StoryGroup, StoryEntry) -> Boolean = { _, _ -> true },
): List<SidebarSection> = groups.groupBy { it.category }.mapNotNull { (category, members) ->
    val listed = members.mapNotNull { group ->
        val entries = sidebarOrder(group.entries).filter { entry ->
            val text = "${category.orEmpty()} ${group.title} ${entry.name}".lowercase()
            words.all { it in text } && include(group, entry)
        }
        if (entries.isEmpty()) null else SidebarGroup(group, entries)
    }
    if (listed.isEmpty()) null else SidebarSection(category, listed)
}

/** The parts of [text] that [words] matched, in order and without overlaps. */
fun matchedRanges(text: String, words: List<String>): List<IntRange> {
    val found = words.mapNotNull { word ->
        val at = text.indexOf(word, ignoreCase = true)
        if (at < 0) null else at until at + word.length
    }.sortedBy { it.first }
    val merged = mutableListOf<IntRange>()
    found.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged.add(range)
        }
    }
    return merged
}

/** [text] as Pango markup with the matched parts underlined in [color] (`#rrggbb`). */
fun highlighted(text: String, words: List<String>, color: String): String {
    fun escape(part: String) = part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    var at = 0
    return buildString {
        matchedRanges(text, words).forEach { range ->
            append(escape(text.substring(at, range.first)))
            append("<span underline=\"single\" foreground=\"$color\">")
            append(escape(text.substring(range.first, range.last + 1)))
            append("</span>")
            at = range.last + 1
        }
        append(escape(text.substring(at)))
    }
}
