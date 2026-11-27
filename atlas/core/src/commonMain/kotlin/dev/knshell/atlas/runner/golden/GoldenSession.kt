// ---------------------------------------------------------------------------
// One golden run over a list of cases: render, compare with the golden, write the pictures and the
// report; in record mode update the goldens. Rendering is a parameter, so this needs no window.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.golden

import dev.knshell.inspect.comparePng
import dev.knshell.shell.CompositionFailedException
import kotlinx.coroutines.CancellationException

/** The file beside the goldens that says what drew them. */
internal const val ENVIRONMENT_FILE = "environment.txt"

/** The report of the latest run, in the output directory. */
internal const val REPORT_FILE = "report.json"

/** The pictures one case gave, and why its `play` script failed if it did. */
class CaseShots(
    val base: ByteArray,
    /** Further pictures by suffix: a forced state (`hover`) or a snapshot of the script. */
    val extra: List<Pair<String, ByteArray>> = emptyList(),
    val playFailure: String? = null,
)

data class GoldenOptions(
    val goldens: String,
    val output: String,
    val record: Boolean = false,
    /** One story or cell, see [goldenCases]; `null` runs everything. */
    val filter: String? = null,
)

class GoldenSession(
    private val options: GoldenOptions,
    private val cases: List<GoldenCase>,
    private val binary: String,
    /** The pictures of a case; throws when it cannot be rendered. */
    private val capture: suspend (GoldenCase) -> CaseShots,
    /** What draws the pictures; asked once something was captured. */
    private val environment: () -> RenderEnvironment,
    private val log: (String) -> Unit = ::println,
) {
    private val recorded: RenderEnvironment? =
        readText("${options.goldens}/$ENVIRONMENT_FILE")?.let(RenderEnvironment::parse)
    private var current: RenderEnvironment? = null
    private val results = mutableListOf<CaseResult>()
    private val pictures = mutableMapOf<String, ByteArray>()
    private var message: String? = null

    suspend fun run(): GoldenReport {
        clearOutput()
        write(finished = false)
        for ((index, case) in cases.withIndex()) {
            val found = try {
                judgeAll(case, capture(case))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompositionFailedException) {
                // Nothing renders after this, so the other cases cannot be judged.
                message = "the composition failed at ${case.id}; ${cases.size - index - 1} case(s) were not run"
                listOf(failed(case, e))
            } catch (e: Throwable) {
                listOf(failed(case, e))
            }
            results += found
            found.forEach { result ->
                log("golden: [${index + 1}/${cases.size}] ${result.status.name.lowercase()} ${result.id}" +
                    (result.error?.let { ": $it" } ?: ""))
            }
            if (message != null) break
            write(finished = false)
        }
        if (options.filter == null) addOrphans()
        if (options.record) record()
        message?.let { log("golden: $it") }
        log(summary())
        return write(finished = true)
    }

    private fun failed(case: GoldenCase, e: Throwable) =
        CaseResult(case.id, case.story, case.path, CaseStatus.Error, error = e.message ?: e::class.simpleName)

    private fun judgeAll(case: GoldenCase, shots: CaseShots): List<CaseResult> {
        val stem = case.path.removeSuffix(".png")
        val judged = listOf(judge(case.id, case.story, case.path, shots.base)) +
            shots.extra.map { (suffix, picture) ->
                judge("${case.id}@$suffix", case.story, "$stem@${safeName(suffix)}.png", picture)
            }
        val failure = shots.playFailure ?: return judged
        return judged +
            CaseResult("${case.id}@play", case.story, "$stem@play.png", CaseStatus.PlayFailed, error = failure)
    }

    private fun judge(id: String, story: String, path: String, actual: ByteArray): CaseResult {
        noteEnvironment()
        pictures[path] = actual
        val golden = readBytes("${options.goldens}/$path")
        val base = CaseResult(id, story, path, CaseStatus.New)
        if (golden == null) {
            // Compared with itself only to learn its size (and that it is a readable picture).
            val size = comparePng(actual, actual)
            writeBytes("${options.output}/${base.actualFile}", actual)
            return base.copy(width = size.actualWidth, height = size.actualHeight)
        }
        val difference = comparePng(golden, actual)
        val compared = base.copy(
            status = if (difference.isEqual) CaseStatus.Same else CaseStatus.Changed,
            differingPixels = difference.differingPixels,
            width = difference.actualWidth,
            height = difference.actualHeight,
            expectedWidth = difference.expectedWidth,
            expectedHeight = difference.expectedHeight,
        )
        difference.diff?.let { diff ->
            writeBytes("${options.output}/${compared.expectedFile}", golden)
            writeBytes("${options.output}/${compared.actualFile}", actual)
            writeBytes("${options.output}/${compared.diffFile}", diff)
        }
        return compared
    }

    /** Learns the environment at the first picture, and says at once when the goldens had another. */
    private fun noteEnvironment() {
        if (current != null) return
        val now = environment()
        current = now
        val differences = recorded?.differencesTo(now).orEmpty()
        if (differences.isNotEmpty()) {
            log("golden: the environment differs from the one the goldens were recorded in: ${differences.joinToString()}")
        }
    }

    private fun addOrphans() {
        // The pictures a case did not get to take, because it failed or never ran, are missing and
        // not orphaned.
        val judged = results.map { it.path }.toSet()
        val failed = results.filter { it.status == CaseStatus.Error || it.status == CaseStatus.PlayFailed }
        val unfinished = (failed.map { it.path } + cases.map { it.path }.filterNot { it in judged })
            .map(::stemOf).toSet()
        val environments = cases.mapNotNull { it.environment?.name }.map(::safeName).toSet()
        listFiles(options.goldens, ".png")
            .filterNot { it in judged || stemOf(it) in unfinished }
            .forEach { path ->
                val id = path.removeSuffix(".png")
                val names = stemOf(path).split('/')
                val story = names.drop(if (names.first() in environments) 1 else 0).take(2).joinToString("/")
                results += CaseResult(id, story, path, CaseStatus.Orphan)
                log("golden: orphan $path: no story produces it (delete it if the story is gone)")
            }
    }

    /** The path of a case's pictures without the extension and the state or snapshot suffix. */
    private fun stemOf(path: String): String = path.removeSuffix(".png").substringBefore('@')

    /** All or nothing: a golden set is only rewritten when every case rendered. */
    private fun record() {
        val now = current
        if (results.any { it.status == CaseStatus.Error || it.status == CaseStatus.PlayFailed }) {
            message = message ?: "nothing was written: some cases failed"
            return
        }
        if (now != null && recorded != null && now != recorded && options.filter != null) {
            message = "nothing was written: the environment differs from the recorded one, record without a story filter"
            return
        }
        results.filter { it.status == CaseStatus.New || it.status == CaseStatus.Changed }.forEach { result ->
            writeBytes("${options.goldens}/${result.path}", pictures.getValue(result.path))
        }
        if (now != null && now != recorded) writeText("${options.goldens}/$ENVIRONMENT_FILE", now.toText())
    }

    private fun summary(): String {
        val counts = CaseStatus.entries
            .map { status -> status to results.count { it.status == status } }
            .filter { it.second > 0 }
            .joinToString { (status, count) -> "$count ${status.name.lowercase()}" }
        return "golden: ${if (options.record) "record" else "check"}: ${counts.ifEmpty { "no cases" }}"
    }

    /** Removes the pictures a previous run left, so that none of them passes for a result of this one. */
    private fun clearOutput() {
        removeFile("${options.output}/$REPORT_FILE")
        listFiles(options.output, ".png")
            .filter { it.endsWith(".expected.png") || it.endsWith(".actual.png") || it.endsWith(".diff.png") }
            .forEach { removeFile("${options.output}/$it") }
    }

    private fun write(finished: Boolean): GoldenReport {
        val report = GoldenReport(
            goldens = options.goldens,
            output = options.output,
            record = options.record,
            binary = binary,
            environment = current,
            recorded = recorded,
            total = cases.size,
            finished = finished,
            cases = results.toList(),
            message = message,
        )
        writeText("${options.output}/$REPORT_FILE", report.toJson())
        return report
    }
}

/**
 * The exit status of a finished run: 0 when it did what was asked. A check passes only when every
 * case matches its golden and no golden is left over; a record only when it wrote the goldens.
 */
fun exitCode(report: GoldenReport): Int {
    val passed = report.finished && report.message == null &&
        if (report.record) {
            report.count(CaseStatus.Error) == 0 && report.count(CaseStatus.PlayFailed) == 0
        } else {
            report.cases.all { it.status == CaseStatus.Same }
        }
    return if (passed) 0 else 1
}
