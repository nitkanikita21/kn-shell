// ---------------------------------------------------------------------------
// AtlasCommands — the request handler of the inspector socket. One JSON request per line, one JSON
// answer per line. `tools/list` and `tools/call` answer in MCP shapes so the daemon can pass them
// through; any other method is a protocol error. The tool schemas live in AtlasToolSchemas.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalEncodingApi::class)

package dev.knshell.atlas.runner

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.InteractionState
import dev.knshell.inspect.Inspector
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Answers one protocol request. The [inspector] reads and acts on the window; [idle] waits until the
 * runtime settled, so that the next request sees the UI the previous one produced. Tools that only
 * touch the atlas state (stories, args) never reach the inspector.
 */
class AtlasCommands(
    private val service: AtlasHostService,
    private val inspector: Inspector,
    private val idle: suspend () -> Unit = inspector::awaitIdle,
) {
    /** One request line in, one answer line out (compact JSON, no raw newline). Never throws except on cancellation. */
    suspend fun handle(line: String): String {
        val response = try {
            dispatch(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorResponse(JsonNull, "internal error: ${e.message}")
        }
        return Json.encodeToString(JsonElement.serializer(), response)
    }

    private suspend fun dispatch(line: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(line)
        } catch (e: Exception) {
            return errorResponse(JsonNull, "malformed JSON: ${e.message}")
        }
        val request = parsed as? JsonObject ?: return errorResponse(JsonNull, "request must be a JSON object")
        val id = request["id"] ?: JsonNull
        val method = request["method"].stringOrNull() ?: return errorResponse(id, "missing method")
        return when (method) {
            "tools/list" -> result(id, buildJsonObject { put("tools", AtlasTools) })
            "tools/call" -> result(id, callTool(request["params"] as? JsonObject ?: JsonObject(emptyMap())))
            else -> errorResponse(id, "unknown method \"$method\"")
        }
    }

    /** Runs one tool; every failure, unknown tools included, becomes an `isError` result. */
    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"].stringOrNull() ?: return toolResult(listOf(text("missing tool name")), isError = true)
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            toolResult(runTool(name, arguments), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolResult(listOf(text(e.message ?: "tool \"$name\" failed")), isError = true)
        }
    }

    private suspend fun runTool(name: String, args: JsonObject): List<JsonObject> = when (name) {
        "list_stories" -> listStories()
        "select_story" -> selectStory(args)
        "get_args" -> json(argsReport(requireSelection()))
        "set_args" -> setArgs(args)
        "reset_args" -> resetArgs()
        "set_view" -> setView(args)
        "tree" -> tree(args)
        "screenshot" -> screenshot(args)
        "click" -> act(args) { id -> inspector.click(id) }
        "set_text" -> act(args) { id -> inspector.setText(id, args.str("text")) }
        "set_checked" -> act(args) { id -> inspector.setChecked(id, args.bool("checked")) }
        "set_value" -> act(args) { id -> inspector.setValue(id, args.float("value")) }
        "select" -> act(args) { id -> inspector.select(id, args.int("index")) }
        "set_state" -> act(args) { id -> inspector.setState(id, args.state(), args.bool("on")) }
        else -> throw ToolError("unknown tool \"$name\"")
    }

    // --- atlas state: no inspector calls here -------------------------------------------------

    private fun listStories(): List<JsonObject> {
        val current = service.selection.value
        val stories = service.groups.flatMap { group ->
            group.entries.map { entry ->
                buildJsonObject {
                    put("name", "${group.title}/${entry.name}")
                    put("kind", if (entry is Variants) "variants" else "story")
                    put("selected", current != null && current.group === group && current.entry === entry)
                    if (entry is Variants) {
                        put("view", service.viewOf(entry).name.lowercase())
                        put("views", JsonArray(entry.views.map { JsonPrimitive(it.name.lowercase()) }))
                    }
                }
            }
        }
        return json(JsonArray(stories))
    }

    private suspend fun selectStory(args: JsonObject): List<JsonObject> {
        val spec = args.str("story")
        val found = resolveStory(service.groups, spec)
            ?: throw ToolError("unknown story \"$spec\"; known stories: ${storyNames()}")
        service.select(found.group, found.entry)
        idle()
        return json(argsReport(found))
    }

    private suspend fun setArgs(args: JsonObject): List<JsonObject> {
        val selection = requireSelection()
        val values = args["values"] as? JsonObject
            ?: throw ToolError("\"values\" must be an object mapping arg names to values")
        val panel = panelArgs(selection)
        val problems = mutableListOf<String>()
        val parsed = mutableListOf<Pair<Arg<Any?>, Any?>>()
        for ((name, value) in values) {
            val arg = panel.firstOrNull { it.name == name }
            if (arg == null) {
                problems += "unknown arg \"$name\""
                continue
            }
            try {
                parsed += arg.coerce(value)
            } catch (e: ToolError) {
                problems += e.message.orEmpty()
            }
        }
        // All-or-nothing: nothing is written before every entry checked out.
        if (problems.isNotEmpty()) throw ToolError(problems.joinToString("; "))
        val live = service.argsFor(selection)
        parsed.forEach { (arg, value) -> live.set(arg, value) }
        idle()
        return json(argsReport(selection))
    }

    private suspend fun resetArgs(): List<JsonObject> {
        val selection = requireSelection()
        service.argsFor(selection).reset()
        idle()
        return json(argsReport(selection))
    }

    private suspend fun setView(args: JsonObject): List<JsonObject> {
        val selection = requireSelection()
        val matrix = selection.entry as? Variants
            ?: throw ToolError("\"${selection.entry.name}\" is not a matrix: only variants have views")
        val wanted = args.str("view")
        val view = matrix.views.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: throw ToolError(
                "\"view\" must be one of ${matrix.views.joinToString { it.name.lowercase() }}; got \"$wanted\"",
            )
        service.setView(matrix, view)
        idle()
        return listStories()
    }

    private fun requireSelection(): Selection =
        service.selection.value ?: throw ToolError("no story is selected; call select_story first")

    private fun storyNames(): String =
        service.groups.flatMap { group -> group.entries.map { "${group.title}/${it.name}" } }.joinToString()

    /** The args the panel shows for [selection]: axis args of a matrix are set per cell, not here. */
    private fun panelArgs(selection: Selection): List<Arg<*>> = when (val entry = selection.entry) {
        is Variants -> selection.group.args.filterNot { it in entry.axisArgs }
        else -> selection.group.args
    }

    private fun argsReport(selection: Selection): JsonObject {
        val live = service.argsFor(selection)
        return buildJsonObject {
            put("selected", "${selection.group.title}/${selection.entry.name}")
            put("args", JsonArray(panelArgs(selection).map { it.describe(live) }))
        }
    }

    // --- the window: idle before reading or after acting ------------------------------------

    private suspend fun tree(args: JsonObject): List<JsonObject> {
        idle()
        val depth = args.optInt("depth")?.coerceAtLeast(0) ?: Int.MAX_VALUE
        val node = args.targetOrNull()?.let { inspector.find(it) } ?: inspector.tree()
        return json(node.toJson(depth))
    }

    private suspend fun screenshot(args: JsonObject): List<JsonObject> {
        idle()
        val id = args.targetOrNull()?.let { inspector.find(it).id }
        val png = inspector.screenshot(id)
        return listOf(
            buildJsonObject {
                put("type", "image")
                put("data", Base64.Default.encode(png))
                put("mimeType", "image/png")
            },
        )
    }

    /** Runs [perform] on the node named by `target`, then settles and answers with its id. */
    private suspend fun act(args: JsonObject, perform: (Int) -> Unit): List<JsonObject> {
        val id = inspector.find(args.target()).id
        perform(id)
        idle()
        return json(buildJsonObject {
            put("ok", true)
            put("id", id)
        })
    }

    // --- JSON in and out -------------------------------------------------------------------

    private fun InspectNode.toJson(depth: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        testTag?.let { put("testTag", it) }
        putJsonObject("bounds") {
            put("x", bounds.x)
            put("y", bounds.y)
            put("width", bounds.width)
            put("height", bounds.height)
        }
        if (cssClasses.isNotEmpty()) put("cssClasses", JsonArray(cssClasses.map { JsonPrimitive(it) }))
        text?.let { put("text", it) }
        checked?.let { put("checked", it) }
        value?.let { put("value", it) }
        selected?.let { put("selected", it) }
        options?.let { put("options", JsonArray(it.map { option -> JsonPrimitive(option) })) }
        put("visible", visible)
        put("sensitive", sensitive)
        if (children.isNotEmpty() && depth > 0) {
            put("children", JsonArray(children.map { it.toJson(depth - 1) }))
        }
    }

    private fun Arg<*>.describe(live: LiveArgs): JsonObject = buildJsonObject {
        put("name", name)
        put("type", typeName())
        put("value", jsonOf(valueIn(live)))
        put("default", jsonOf(default))
        when (val arg = this@describe) {
            is Arg.Choice<*> -> put("options", JsonArray(arg.options.map { JsonPrimitive(it.toString()) }))
            is Arg.IntNum -> put("range", rangeJson(arg.range.first, arg.range.last))
            is Arg.FloatNum -> put("range", rangeJson(arg.range.start, arg.range.endInclusive))
            else -> Unit
        }
    }

    private fun Arg<*>.typeName(): String = when (this) {
        is Arg.Text -> "string"
        is Arg.Flag -> "boolean"
        is Arg.Choice<*> -> "enum"
        is Arg.IntNum -> "int"
        is Arg.FloatNum -> "float"
    }

    @Suppress("UNCHECKED_CAST")
    private fun Arg<*>.valueIn(live: LiveArgs): Any? = live[this as Arg<Any?>]

    /** Validates one raw JSON value against this arg; the error names the arg and what it expected. */
    @Suppress("UNCHECKED_CAST")
    private fun Arg<*>.coerce(json: JsonElement): Pair<Arg<Any?>, Any?> = (this as Arg<Any?>) to parseValue(this, json)

    private fun parseValue(arg: Arg<*>, json: JsonElement): Any? {
        fun expected(what: String): Nothing = throw ToolError("arg \"${arg.name}\" expects $what, got $json")
        return when (arg) {
            is Arg.Text -> json.stringOrNull() ?: expected("a string")
            is Arg.Flag -> json.boolOrNull() ?: expected("a boolean")
            is Arg.Choice<*> -> {
                val options = arg.options.joinToString()
                val label = json.stringOrNull() ?: expected("one of $options")
                arg.options.firstOrNull { it.toString().equals(label, ignoreCase = true) }
                    ?: expected("one of $options")
            }
            is Arg.IntNum -> json.numberText()?.toIntOrNull()?.takeIf { it in arg.range }
                ?: expected("an integer in ${arg.range.first}..${arg.range.last}")
            is Arg.FloatNum -> json.numberText()?.toFloatOrNull()?.takeIf { it in arg.range }
                ?: expected("a number in ${arg.range.start}..${arg.range.endInclusive}")
        }
    }

    private fun rangeJson(min: Number, max: Number): JsonObject = buildJsonObject {
        put("min", min)
        put("max", max)
    }

    private fun jsonOf(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun errorResponse(id: JsonElement, message: String): JsonObject = buildJsonObject {
        put("id", id)
        putJsonObject("error") { put("message", message) }
    }

    private fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("result", result)
    }

    private fun toolResult(content: List<JsonObject>, isError: Boolean): JsonObject = buildJsonObject {
        put("content", JsonArray(content))
        put("isError", isError)
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    /** A text item holding [value] as compact JSON. */
    private fun json(value: JsonElement): List<JsonObject> =
        listOf(text(Json.encodeToString(JsonElement.serializer(), value)))
}

/** A request the tool cannot carry out: bad or missing arguments, no selection, unknown names. */
private class ToolError(message: String) : Exception(message)

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun JsonElement.numberText(): String? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.content

private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.str(key: String): String =
    primitive(key)?.takeIf { it.isString }?.content ?: throw ToolError("\"$key\" must be a string")

private fun JsonObject.bool(key: String): Boolean =
    primitive(key)?.let { it.takeUnless { p -> p.isString }?.booleanOrNull }
        ?: throw ToolError("\"$key\" must be a boolean")

private fun JsonObject.optInt(key: String): Int? = primitive(key)?.let { p ->
    p.takeUnless { it.isString }?.content?.toIntOrNull() ?: throw ToolError("\"$key\" must be an integer")
}

private fun JsonObject.int(key: String): Int = optInt(key) ?: throw ToolError("\"$key\" is required and must be an integer")

private fun JsonObject.float(key: String): Float =
    primitive(key)?.let { it.takeUnless { p -> p.isString }?.content?.toFloatOrNull() }
        ?: throw ToolError("\"$key\" is required and must be a number")

private fun JsonObject.targetOrNull(): String? = primitive("target")?.content

private fun JsonObject.target(): String =
    targetOrNull() ?: throw ToolError("\"target\" is required: a node id (number) or a testTag (string)")

private fun JsonObject.state(): InteractionState = when (val name = str("state")) {
    "hover" -> InteractionState.Hover
    "pressed" -> InteractionState.Pressed
    "focus" -> InteractionState.Focus
    else -> throw ToolError("\"state\" must be one of hover, pressed, focus; got \"$name\"")
}
