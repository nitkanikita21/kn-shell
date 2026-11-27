// ---------------------------------------------------------------------------
// What a golden run found, as data and as `report.json`: the run writes it after every case, the
// atlas window reads it to show the differences.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class CaseStatus {
    Same,
    Changed,

    /** There is no golden yet. */
    New,

    /** The story could not be rendered. */
    Error,

    /** The story's `play` script failed. */
    PlayFailed,

    /** A golden that no story produces. */
    Orphan,
}

data class CaseResult(
    val id: String,
    val story: String,
    /** Golden file, relative to the golden directory. */
    val path: String,
    val status: CaseStatus,
    val differingPixels: Int = 0,
    /** Size of the new picture, 0 when there is none. */
    val width: Int = 0,
    val height: Int = 0,
    /** Size of the golden it was compared with, 0 when there is none. */
    val expectedWidth: Int = 0,
    val expectedHeight: Int = 0,
    val error: String? = null,
) {
    private val stem: String get() = path.removeSuffix(".png")

    /** The pictures of a differing case, relative to the output directory. */
    val expectedFile: String get() = "$stem.expected.png"
    val actualFile: String get() = "$stem.actual.png"
    val diffFile: String get() = "$stem.diff.png"
}

/** What drew the pictures: see `Inspector.renderEnvironment`. */
data class RenderEnvironment(val values: List<Pair<String, String>>) {
    /** The content of `environment.txt`. */
    fun toText(): String = values.joinToString("") { (key, value) -> "$key $value\n" }

    /** What changed from this one to [other], one `key: old → new` per difference. */
    fun differencesTo(other: RenderEnvironment): List<String> {
        val mine = values.toMap()
        val theirs = other.values.toMap()
        return (mine.keys + theirs.keys).distinct()
            .filter { mine[it] != theirs[it] }
            .map { "$it: ${mine[it] ?: "?"} → ${theirs[it] ?: "?"}" }
    }

    companion object {
        fun parse(text: String): RenderEnvironment = RenderEnvironment(
            text.lines().filter { ' ' in it }.map { it.substringBefore(' ') to it.substringAfter(' ') },
        )
    }
}

data class GoldenReport(
    val goldens: String,
    val output: String,
    val record: Boolean,
    /** [fileStamp] of the atlas binary that made the pictures. */
    val binary: String,
    /** What drew this run; `null` until the first picture. */
    val environment: RenderEnvironment?,
    /** What drew the goldens; `null` when none were recorded. */
    val recorded: RenderEnvironment?,
    /** Cases the run set out to render. */
    val total: Int,
    val finished: Boolean,
    val cases: List<CaseResult>,
    /** Why the run did not do what was asked, if it did not. */
    val message: String? = null,
) {
    val environmentDiffers: Boolean
        get() = environment != null && recorded != null && environment != recorded

    fun count(status: CaseStatus): Int = cases.count { it.status == status }

    fun toJson(): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("goldens", goldens)
            put("output", output)
            put("record", record)
            put("binary", binary)
            environment?.let { put("environment", it.toText()) }
            recorded?.let { put("recorded", it.toText()) }
            put("total", total)
            put("finished", finished)
            message?.let { put("message", it) }
            putJsonArray("cases") {
                cases.forEach { case ->
                    add(
                        buildJsonObject {
                            put("id", case.id)
                            put("story", case.story)
                            put("path", case.path)
                            put("status", case.status.name)
                            put("differingPixels", case.differingPixels)
                            put("width", case.width)
                            put("height", case.height)
                            put("expectedWidth", case.expectedWidth)
                            put("expectedHeight", case.expectedHeight)
                            case.error?.let { put("error", it) }
                        },
                    )
                }
            }
        },
    )

    companion object {
        /** The report in [text], or `null` when it is not one. */
        fun parse(text: String): GoldenReport? = try {
            val json = Json.parseToJsonElement(text).jsonObject
            GoldenReport(
                goldens = json.string("goldens")!!,
                output = json.string("output")!!,
                record = json.getValue("record").jsonPrimitive.boolean,
                binary = json.string("binary")!!,
                environment = json.string("environment")?.let(RenderEnvironment::parse),
                recorded = json.string("recorded")?.let(RenderEnvironment::parse),
                total = json.int("total"),
                finished = json.getValue("finished").jsonPrimitive.boolean,
                message = json.string("message"),
                cases = (json.getValue("cases") as JsonArray).jsonArray.map { element ->
                    val case = element.jsonObject
                    CaseResult(
                        id = case.string("id")!!,
                        story = case.string("story")!!,
                        path = case.string("path")!!,
                        status = CaseStatus.valueOf(case.string("status")!!),
                        differingPixels = case.int("differingPixels"),
                        width = case.int("width"),
                        height = case.int("height"),
                        expectedWidth = case.int("expectedWidth"),
                        expectedHeight = case.int("expectedHeight"),
                        error = case.string("error"),
                    )
                },
            )
        } catch (e: Exception) {
            null
        }

        private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    }
}
