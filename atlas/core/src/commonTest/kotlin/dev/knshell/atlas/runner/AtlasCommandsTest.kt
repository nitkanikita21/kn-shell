package dev.knshell.atlas.runner

import dev.knshell.atlas.Size
import dev.knshell.atlas.arg
import dev.knshell.atlas.sampleGroup
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.inspect.windowInspector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The socket protocol of the atlas, driven without a window: only tools that never touch the
 * inspector are called, so [windowInspector] stays unmounted and GTK is never initialised.
 */
class AtlasCommandsTest {

    /** A fresh host with the sample group ("Button": Primary, Sizes, Matrix) and its commands. */
    private class Harness {
        val group = sampleGroup()
        val service = AtlasHostService(listOf(group))
        val commands = AtlasCommands(service, windowInspector(), idle = {})
    }

    // --- protocol ---

    @Test
    fun `tools list names every atlas tool with an input schema`() {
        val tools = Harness().commands.rpc("""{"id":1,"method":"tools/list"}""")["result"]!!
            .jsonObject["tools"]!!.jsonArray

        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        listOf(
            "list_stories", "select_story", "get_args", "set_args", "reset_args", "tree",
            "screenshot", "click", "set_text", "set_checked", "set_value", "select", "set_state", "set_view",
        ).forEach { assertContains(names, it) }
        tools.forEach { tool ->
            assertNotNull(tool.jsonObject["inputSchema"], "no inputSchema in $tool")
        }
    }

    @Test
    fun `set view switches the selected matrix and list stories reports it`() {
        val harness = Harness()
        harness.commands.tool("select_story", """{"story":"Button/Matrix"}""")

        val result = harness.commands.tool("set_view", """{"view":"TABLE"}""")

        assertFalse(isError(result), result.toString())
        val matrix = harness.group.entries.single { it.name == "Matrix" } as Variants
        assertEquals(MatrixView.Table, harness.service.viewOf(matrix))
        assertContains(result.toString(), "table")
    }

    @Test
    fun `set view refuses a plain story and an unknown view`() {
        val harness = Harness()
        harness.commands.tool("select_story", """{"story":"Button/Primary"}""")
        assertTrue(isError(harness.commands.tool("set_view", """{"view":"table"}""")))

        harness.commands.tool("select_story", """{"story":"Button/Matrix"}""")
        assertTrue(isError(harness.commands.tool("set_view", """{"view":"board"}""")))
    }

    @Test
    fun `search finds stories by words`() {
        val result = Harness().commands.tool("search_stories", """{"query":"button matr"}""")

        assertFalse(isError(result))
        assertContains(result.toString(), "Button/Matrix")
        assertFalse("Button/Primary" in result.toString())
    }

    @Test
    fun `set width takes the preset widths only`() {
        val harness = Harness()

        assertFalse(isError(harness.commands.tool("set_width", """{"width":320}""")))
        assertEquals(320, harness.service.view.width)
        assertTrue(isError(harness.commands.tool("set_width", """{"width":321}""")))
        harness.commands.tool("set_width", """{"width":0}""")
        assertNull(harness.service.view.width)
    }

    @Test
    fun `checking needs a golden configuration`() {
        val result = Harness().commands.tool("check_story", """{"story":"Button/Primary"}""")

        assertTrue(isError(result))
        assertContains(result.toString(), "golden configuration")
    }

    @Test
    fun `unknown tool is reported as an error result`() {
        val result = Harness().commands.tool("no_such_tool")

        assertTrue(isError(result))
    }

    @Test
    fun `unknown method is reported as an error object`() {
        val response = Harness().commands.rpc("""{"id":5,"method":"no/such/method"}""")

        assertNotNull(response["error"])
        assertNull(response["result"])
        assertEquals(5, response["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `malformed JSON is reported as an error object`() {
        val response = Harness().commands.rpc("{not json")

        assertNotNull(response["error"])
        assertNull(response["result"])
    }

    @Test
    fun `every response is a single line`() {
        val harness = Harness()
        val requests = listOf(
            """{"id":1,"method":"tools/list"}""",
            """{"id":2,"method":"tools/call","params":{"name":"list_stories","arguments":{}}}""",
            """{"id":3,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}""",
            "{not json",
        )

        requests.forEach { request ->
            val line = runBlocking { harness.commands.handle(request) }
            assertFalse(line.contains('\n'), "response spans several lines: $line")
        }
    }

    // --- stories ---

    @Test
    fun `list stories names every story of every group`() {
        val text = textOf(Harness().commands.tool("list_stories"))

        assertContains(text, "Button/Primary")
        assertContains(text, "Button/Sizes")
        assertContains(text, "Button/Matrix")
    }

    @Test
    fun `select story changes the selection`() {
        val harness = Harness()

        val result = harness.commands.tool("select_story", """{"story":"Button/Sizes"}""")

        assertFalse(isError(result))
        assertEquals("Sizes", harness.service.selection.value?.entry?.name)
    }

    @Test
    fun `select story to a later story replaces the earlier one`() {
        val harness = Harness()
        harness.commands.tool("select_story", """{"story":"Button/Primary"}""")

        harness.commands.tool("select_story", """{"story":"Button/Matrix"}""")

        assertEquals("Matrix", harness.service.selection.value?.entry?.name)
    }

    @Test
    fun `select story with an unknown name is an error and keeps the selection`() {
        val harness = Harness()
        harness.commands.tool("select_story", """{"story":"Button/Primary"}""")

        val unknown = harness.commands.tool("select_story", """{"story":"Button/Nope"}""")
        val malformed = harness.commands.tool("select_story", """{"story":"Primary"}""")

        assertTrue(isError(unknown))
        assertTrue(isError(malformed))
        assertEquals("Primary", harness.service.selection.value?.entry?.name)
    }

    // --- args ---

    @Test
    fun `get args reports the names and defaults of the selected story`() {
        val harness = Harness()
        harness.commands.tool("select_story", """{"story":"Button/Primary"}""")

        val result = harness.commands.tool("get_args")
        val text = textOf(result)

        assertFalse(isError(result))
        for (name in listOf("text", "enabled", "size", "n", "f")) assertContains(text, name)
        assertContains(text, "Save")
        assertContains(text, "0.5")
    }

    @Test
    fun `set args sets a string`() {
        val harness = Harness()
        val text = selectPrimary(harness)

        val result = harness.commands.tool("set_args", """{"values":{"text":"Send"}}""")

        assertFalse(isError(result))
        assertEquals("Send", liveArgs(harness).get(text))
    }

    @Test
    fun `set args sets a boolean`() {
        val harness = Harness()
        selectPrimary(harness)
        val enabled = harness.group.arg<Arg.Flag>("enabled")

        harness.commands.tool("set_args", """{"values":{"enabled":false}}""")

        assertEquals(false, liveArgs(harness).get(enabled))
    }

    @Test
    fun `set args sets an enum by its name ignoring case`() {
        val harness = Harness()
        selectPrimary(harness)
        val size = harness.group.arg<Arg.Choice<Size>>("size")

        assertFalse(isError(harness.commands.tool("set_args", """{"values":{"size":"m"}}""")))
        assertEquals(Size.M, liveArgs(harness).get(size))

        assertFalse(isError(harness.commands.tool("set_args", """{"values":{"size":"L"}}""")))
        assertEquals(Size.L, liveArgs(harness).get(size))
    }

    @Test
    fun `set args sets an integer inside its range`() {
        val harness = Harness()
        selectPrimary(harness)
        val n = harness.group.arg<Arg.IntNum>("n")

        harness.commands.tool("set_args", """{"values":{"n":32}}""")

        assertEquals(32, liveArgs(harness).get(n))
    }

    @Test
    fun `set args sets a float inside its range`() {
        val harness = Harness()
        selectPrimary(harness)
        val f = harness.group.arg<Arg.FloatNum>("f")

        harness.commands.tool("set_args", """{"values":{"f":0.25}}""")

        assertEquals(0.25f, liveArgs(harness).get(f))
    }

    @Test
    fun `set args sets several values at once`() {
        val harness = Harness()
        selectPrimary(harness)
        val text = harness.group.arg<Arg.Text>("text")
        val enabled = harness.group.arg<Arg.Flag>("enabled")

        harness.commands.tool("set_args", """{"values":{"text":"Go","enabled":false}}""")

        assertEquals("Go", liveArgs(harness).get(text))
        assertEquals(false, liveArgs(harness).get(enabled))
    }

    @Test
    fun `set args is all or nothing when one value is bad`() {
        val harness = Harness()
        selectPrimary(harness)
        val text = harness.group.arg<Arg.Text>("text")
        val n = harness.group.arg<Arg.IntNum>("n")

        val result = harness.commands.tool("set_args", """{"values":{"text":"Hi","n":999}}""")

        assertTrue(isError(result))
        assertEquals("Save", liveArgs(harness).get(text))
        assertEquals(16, liveArgs(harness).get(n))
    }

    @Test
    fun `set args rejects an integer out of range`() {
        val harness = Harness()
        selectPrimary(harness)

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"n":100}}""")))
        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"n":-1}}""")))
    }

    @Test
    fun `set args rejects a float out of range`() {
        val harness = Harness()
        selectPrimary(harness)

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"f":2.0}}""")))
    }

    @Test
    fun `set args rejects an unknown enum name`() {
        val harness = Harness()
        selectPrimary(harness)

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"size":"XL"}}""")))
    }

    @Test
    fun `set args rejects a value of the wrong type`() {
        val harness = Harness()
        selectPrimary(harness)

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"enabled":"yes"}}""")))
        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"n":"3"}}""")))
        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"text":5}}""")))
    }

    @Test
    fun `set args rejects an unknown argument name`() {
        val harness = Harness()
        selectPrimary(harness)

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"nope":1}}""")))
    }

    @Test
    fun `set args without a selection is an error`() {
        val harness = Harness()

        assertTrue(isError(harness.commands.tool("set_args", """{"values":{"text":"Hi"}}""")))
    }

    @Test
    fun `reset args restores the defaults`() {
        val harness = Harness()
        selectPrimary(harness)
        val text = harness.group.arg<Arg.Text>("text")
        harness.commands.tool("set_args", """{"values":{"text":"Hi"}}""")

        val result = harness.commands.tool("reset_args")

        assertFalse(isError(result))
        assertEquals("Save", liveArgs(harness).get(text))
    }

    // --- helpers ---

    /** Selects the "Button/Primary" story and returns the group's text argument. */
    private fun selectPrimary(harness: Harness): Arg<String> {
        harness.commands.tool("select_story", """{"story":"Button/Primary"}""")
        return harness.group.arg("text")
    }

    private fun liveArgs(harness: Harness) =
        harness.service.argsFor(assertNotNull(harness.service.selection.value))

    /** Sends one `tools/call` request and returns the `result` object. */
    private fun AtlasCommands.tool(name: String, arguments: String = "{}"): JsonObject =
        rpc("""{"id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""")["result"]!!
            .jsonObject

    /** Sends one raw request line and parses the single-line response. */
    private fun AtlasCommands.rpc(request: String): JsonObject =
        Json.parseToJsonElement(runBlocking { handle(request) }).jsonObject

    private fun isError(result: JsonObject): Boolean =
        result["isError"]!!.jsonPrimitive.boolean

    /** All `text` payloads of a tool result, joined. */
    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray.joinToString("\n") {
            it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
}
