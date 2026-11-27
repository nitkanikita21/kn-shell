package dev.knshell.atlas.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Описи інструментів у формі MCP `tools/list`: name, description, inputSchema (JSON Schema, type object).

// Declared before AtlasTools: top-level properties initialise in textual order.
private val TARGET: JsonObject = buildJsonObject {
    put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("string"))))
    put("description", "Node id (number) or testTag (string) from the tree")
}

/** Every tool of the atlas socket, in the order `tools/list` returns them. */
internal val AtlasTools: JsonArray = JsonArray(
    listOf(
        tool(
            "list_stories",
            "List every story as \"Group/Entry\" with its kind (story or variants) and whether it is selected.",
        ),
        tool(
            "select_story",
            "Select a story by its \"Group/Entry\" name (case-insensitive). The preview and the args panel switch to it.",
            mapOf("story" to field("string", "\"Group/Entry\" name of the story")),
            listOf("story"),
        ),
        tool(
            "get_args",
            "Current values, types, defaults and options or ranges of the args shown in the args panel of the selected story.",
        ),
        tool(
            "set_args",
            "Set several args of the selected story at once. All-or-nothing: if any value is invalid, nothing is set.",
            mapOf("values" to field("object", "Map of arg name to new value: string, boolean, option name, integer or number")),
            listOf("values"),
        ),
        tool(
            "reset_args",
            "Restore every arg of the selected story to its default value.",
        ),
        tool(
            "set_view",
            "Lay the selected matrix out in another view; list_stories tells which views each matrix has.",
            mapOf("view" to field("string", "compose (grouped previews) or table (needs exactly 2 axes)")),
            required = listOf("view"),
        ),
        tool(
            "set_environment",
            "Show the stories in another environment of the catalog, such as its light theme.",
            mapOf("name" to field("string", "Environment name, as the sidebar shows it")),
            listOf("name"),
        ),
        tool(
            "focus",
            "Give the keyboard focus to a widget.",
            mapOf("target" to TARGET),
            listOf("target"),
        ),
        tool(
            "press",
            "Press a key (Enter, Space, Escape, Tab, Up, Down, Left, Right, Home, End, PageUp, PageDown, Backspace, Delete, F1-F12) in the hidden atlas.",
            mapOf(
                "key" to field("string", "Key name"),
                "shift" to field("boolean", "Hold Shift"),
                "ctrl" to field("boolean", "Hold Ctrl"),
                "alt" to field("boolean", "Hold Alt"),
                "meta" to field("boolean", "Hold Super"),
            ),
            listOf("key"),
        ),
        tool(
            "type",
            "Type text (letters, digits, spaces and - . , /) into the focused widget of the hidden atlas.",
            mapOf("text" to field("string", "Text to type")),
            listOf("text"),
        ),
        tool(
            "tree",
            "Compact JSON of the widget tree (id, type, testTag, bounds, text, ARIA role, name, states, focus). Optionally only the subtree of a target.",
            mapOf(
                "target" to TARGET,
                "depth" to field("integer", "How many levels of children to include; 0 is the node alone. Default: all"),
            ),
        ),
        tool(
            "screenshot",
            "PNG of the whole composition, or of one node with its background when a target is given.",
            mapOf("target" to TARGET),
        ),
        tool(
            "click",
            "Click a button, or run the click handler of a widget.",
            mapOf("target" to TARGET),
            listOf("target"),
        ),
        tool(
            "set_text",
            "Replace the text of an Entry, as typing would.",
            mapOf("target" to TARGET, "text" to field("string", "New text")),
            listOf("target", "text"),
        ),
        tool(
            "set_checked",
            "Switch a Switch on or off, as a click would.",
            mapOf("target" to TARGET, "checked" to field("boolean", "New state")),
            listOf("target", "checked"),
        ),
        tool(
            "set_value",
            "Move a Slider to a value inside its range.",
            mapOf("target" to TARGET, "value" to field("number", "New slider value")),
            listOf("target", "value"),
        ),
        tool(
            "select",
            "Select an item of a DropDown by its index.",
            mapOf("target" to TARGET, "index" to field("integer", "Zero-based index of the option")),
            listOf("target", "index"),
        ),
        tool(
            "set_state",
            "Force or release a pseudo-state (hover, pressed, focus) on a node.",
            mapOf(
                "target" to TARGET,
                "state" to buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf("hover", "pressed", "focus").map { JsonPrimitive(it) }))
                    put("description", "State to force")
                },
                "on" to field("boolean", "true to force the state, false to release it"),
            ),
            listOf("target", "state", "on"),
        ),
    ),
)

private fun field(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun tool(
    name: String,
    description: String,
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    putJsonObject("inputSchema") {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}
