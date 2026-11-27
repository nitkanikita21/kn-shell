// ---------------------------------------------------------------------------
// What the atlas remembers between runs, so that a rebuild brings back the same place. Arg values
// are not part of it: a story starts from its defaults.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AtlasMemory(
    /** The selected story as `Group/Entry`. */
    val story: String? = null,
    val environment: String? = null,
    /** The view of each matrix that is not shown in its default one, by `Group/Entry`. */
    val views: Map<String, String> = emptyMap(),
    /** The closed groups of the tree. */
    val closedGroups: List<String> = emptyList(),
    /** The closed groups inside matrices, as `Group/Entry/group`. */
    val closedCells: List<String> = emptyList(),
    val sidebarHidden: Boolean = false,
    val argsHidden: Boolean = false,
    val width: Int? = null,
) {
    fun toJson(): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            story?.let { put("story", it) }
            environment?.let { put("environment", it) }
            put("views", JsonObject(views.mapValues { JsonPrimitive(it.value) }))
            put("closedGroups", JsonArray(closedGroups.map(::JsonPrimitive)))
            put("closedCells", JsonArray(closedCells.map(::JsonPrimitive)))
            put("sidebarHidden", sidebarHidden)
            put("argsHidden", argsHidden)
            width?.let { put("width", it) }
        },
    )

    companion object {
        /** The memory in [text]; an empty one when it cannot be read. */
        fun parse(text: String): AtlasMemory = try {
            val json = Json.parseToJsonElement(text).jsonObject
            fun string(key: String) = json[key]?.jsonPrimitive?.content
            fun list(key: String) = json[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            fun flag(key: String) = json[key]?.jsonPrimitive?.boolean == true
            AtlasMemory(
                story = string("story"),
                environment = string("environment"),
                views = json["views"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                closedGroups = list("closedGroups"),
                closedCells = list("closedCells"),
                sidebarHidden = flag("sidebarHidden"),
                argsHidden = flag("argsHidden"),
                width = json["width"]?.jsonPrimitive?.int,
            )
        } catch (e: IllegalArgumentException) {
            AtlasMemory()
        }
    }
}

/** Longest file name made of an executable's path. */
private const val NAME_LENGTH = 120

/**
 * Where the memory of the atlas at [executable] is kept: below the state directory [stateHome]
 * (`XDG_STATE_HOME`), else below [home]. `null` without either.
 */
fun memoryFile(executable: String, stateHome: String?, home: String?): String? {
    val base = stateHome?.takeIf { it.isNotBlank() }
        ?: home?.takeIf { it.isNotBlank() }?.let { "$it/.local/state" }
        ?: return null
    val name = executable.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").takeLast(NAME_LENGTH)
    return "$base/kn-atlas/$name.json"
}
