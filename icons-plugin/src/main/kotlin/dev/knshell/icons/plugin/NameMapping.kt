package dev.knshell.icons.plugin

/**
 * Naming conventions between upstream icon file names (kebab-case ids such as
 * `wifi-off`) and generated Kotlin property names (PascalCase such as `WifiOff`).
 *
 * The file name is always the ground truth: [toClassName] derives the property
 * name for the catalog, and the usage scanner validates written names against it,
 * so round-trip edge cases (acronyms like `srgb`, digits like `code-2`) cannot
 * produce ids that do not exist upstream.
 */
object NameMapping {

    /** `wifi-off` -> `WifiOff`, `arrow-down-right` -> `ArrowDownRight`, `srgb` -> `Srgb`. */
    fun toClassName(id: String): String =
        id.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    /**
     * `WifiOff` -> `wifi-off`. Acronym runs stay glued to the following word
     * (`Srgb` -> `srgb`, `ChevronsUpDown` -> `chevrons-up-down`), digits start a
     * new word (`Code2` -> `code-2` is NOT produced from file names; the canonical
     * check in the scanner goes through [toClassName] instead).
     */
    fun toId(className: String): String {
        val out = StringBuilder()
        val chars = className
        for (i in chars.indices) {
            val c = chars[i]
            if (c.isUpperCase()) {
                val prevLowerOrDigit = i > 0 && (chars[i - 1].isLowerCase())
                val nextLower = i + 1 < chars.length && chars[i + 1].isLowerCase()
                if (i > 0 && (prevLowerOrDigit || nextLower)) out.append('-')
                out.append(c.lowercaseChar())
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * `(className, id)` pairs for a whole catalog, collision-free and deterministic.
     *
     * Upstream ids can collide after PascalCase mapping (`arrow-down-0-1` and
     * `arrow-down-01` both map to `ArrowDown01`), so the 2nd..nth occurrence of a
     * name gets its ordinal appended (`ArrowDown012`, `ArrowDown013`, …). The rule
     * depends only on the (sorted) id list, so codegen and the usage scanner
     * always agree without sharing state.
     *
     * Ids whose class name would not be a legal Kotlin identifier (empty, or
     * starting with a digit — lucide has `10k`) get no property; `Pack.of("10k")`
     * still works.
     */
    fun catalogEntries(ids: Iterable<String>): List<Pair<String, String>> {
        val seen = HashMap<String, Int>()
        val out = ArrayList<Pair<String, String>>()
        for (id in ids) {
            val base = toClassName(id)
            if (base.isEmpty() || base.first().isDigit()) continue
            val n = seen.merge(base, 1) { a, b -> a + b }
            out += (if (n == 1) base else "$base$n") to id
        }
        return out
    }
}
