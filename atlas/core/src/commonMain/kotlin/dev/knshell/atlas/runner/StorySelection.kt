package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.StoryEntry

data class Selection(val group: StoryGroup, val entry: StoryEntry)

// перша story першої групи, що має entries; null, якщо їх немає
fun defaultSelection(groups: List<StoryGroup>): Selection? {
    val group = groups.firstOrNull { it.entries.isNotEmpty() } ?: return null
    return Selection(group, group.entries.first())
}

// spec у форматі "Group/Entry"; поділ по першому "/", без урахування регістру
fun resolveStory(groups: List<StoryGroup>, spec: String): Selection? {
    val slash = spec.indexOf('/')
    if (slash < 0) return null
    val group = groups.firstOrNull { it.title.equals(spec.substring(0, slash), ignoreCase = true) } ?: return null
    val entry = group.entries.firstOrNull { it.name.equals(spec.substring(slash + 1), ignoreCase = true) } ?: return null
    return Selection(group, entry)
}

// шлях сокета інспектора: `--inspect=<path>` або `--inspect <path>` важливіше за env; порожнє → null
fun parseInspectPath(args: Array<String>, env: String?): String? {
    val fromArgs = args.withIndex().firstNotNullOfOrNull { (i, arg) ->
        when {
            arg == "--inspect" -> args.getOrNull(i + 1)
            arg.startsWith("--inspect=") -> arg.substringAfter('=')
            else -> null
        }
    }
    return fromArgs?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
}

// `--story X` або `--story=X` важливіше за env; порожнє значення → env; порожній env → null
fun parseStorySpec(args: Array<String>, env: String?): String? {
    val fromArgs = args.withIndex().firstNotNullOfOrNull { (i, arg) ->
        when {
            arg == "--story" -> args.getOrNull(i + 1)
            arg.startsWith("--story=") -> arg.substringAfter('=')
            else -> null
        }
    }
    return fromArgs?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
}
