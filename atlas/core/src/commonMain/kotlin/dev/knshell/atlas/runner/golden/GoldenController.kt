// ---------------------------------------------------------------------------
// The golden check as the atlas window sees it. The window never takes the pictures itself: it draws
// on another renderer. It starts the same run Gradle does, as a process of its own, and reads the
// report that run writes.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package dev.knshell.atlas.runner.golden

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.compose.launchOnUiThread
import dev.knshell.process.ChildProcess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.posix.getpid
import platform.posix.readlink

private const val POLL_MS = 200L
private const val PATH_BYTES = 4096

/** How the differing pictures of a case are shown. */
enum class DiffMode(val title: String) {
    Side("Поруч"),
    Diff("Різниця"),
    Flip("Перемикати"),
}

/** The worst status of every story that has something to show; stories that match are left out. */
fun storyStatuses(report: GoldenReport): Map<String, CaseStatus> {
    val worst = listOf(CaseStatus.Error, CaseStatus.PlayFailed, CaseStatus.Changed, CaseStatus.New, CaseStatus.Orphan)
    return report.cases.filter { it.status != CaseStatus.Same }
        .groupBy { it.story }
        .mapValues { (_, cases) -> worst.first { status -> cases.any { it.status == status } } }
}

private val STATUS_NAMES = listOf(
    CaseStatus.Changed to "змінено",
    CaseStatus.New to "нових",
    CaseStatus.Error to "з помилкою",
    CaseStatus.PlayFailed to "сценаріїв упало",
    CaseStatus.Orphan to "зайвих",
)

/** What differs after a run, one line per kind: `змінено: 2`. Empty when everything matches. */
fun differences(report: GoldenReport): List<Pair<CaseStatus, String>> = STATUS_NAMES
    .filter { report.count(it.first) > 0 }
    .map { (status, name) -> status to "$name: ${report.count(status)}" }

/** One line that says how a run went. */
fun summaryText(report: GoldenReport): String {
    if (!report.finished) return "${report.cases.size}/${report.total}"
    return differences(report).joinToString(" · ") { it.second }
        .ifEmpty { "без змін: ${report.count(CaseStatus.Same)}" }
}

class GoldenController(
    /** Read again for every check: a Gradle run may have rewritten it. `null` when there is none. */
    private val config: () -> GoldenConfig?,
    /** [fileStamp] of the executable showing the window. */
    val binaryStamp: String,
    /** A path that starts that same executable. */
    private val ownBinary: String,
) {
    val available: Boolean = config() != null

    var report by mutableStateOf<GoldenReport?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    /** What went wrong with the last action. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Grows whenever the pictures on disk may have changed. */
    var generation by mutableStateOf(0)
        private set
    var onlyChanged by mutableStateOf(false)
    var mode by mutableStateOf(DiffMode.Side)

    init {
        // The result of an earlier check is still worth showing; `blocker` says when it is stale.
        config()?.let { current ->
            report = read(current)?.takeIf { !it.record && it.finished && it.goldens == current.goldens }
        }
    }

    /** Starts a check in a process of its own and follows its report. */
    fun check() {
        val current = config() ?: return
        if (running) return
        running = true
        problem = null
        report = null
        launchOnUiThread {
            try {
                removeFile("${current.output}/$REPORT_FILE")
                val process = ChildProcess.start(current.command(ownBinary), current.environment)
                try {
                    while (process.isRunning) {
                        delay(POLL_MS)
                        read(current)?.let { report = it }
                    }
                    report = read(current)
                    if (report?.finished != true) {
                        problem = "прогін не завершився (код ${process.exitStatus}), деталі у виводі атласу"
                    }
                } finally {
                    process.close()
                }
            } catch (e: Exception) {
                problem = e.message
            } finally {
                running = false
                generation++
            }
        }
    }

    /** Why [scope] cannot be accepted now, or `null` when it can. */
    fun blocker(scope: AcceptScope): String? = when {
        running -> "перевірка ще триває"
        else -> report?.acceptBlocker(scope, binaryStamp) ?: if (report == null) "ще нічого не перевірено" else null
    }

    /** Makes the new pictures of [scope] the goldens. */
    fun take(scope: AcceptScope) {
        val current = report ?: return
        if (running) return
        try {
            report = accept(current, scope, binaryStamp)
            problem = null
        } catch (e: Exception) {
            problem = e.message
        }
        generation++
    }

    private fun read(config: GoldenConfig): GoldenReport? =
        readText("${config.output}/$REPORT_FILE")?.let(GoldenReport::parse)

    companion object {
        /** The controller of the running atlas, configured by the file beside its executable. */
        fun forThisProcess(): GoldenController {
            val directory = executablePath()?.substringBeforeLast('/')
            return GoldenController(
                config = {
                    directory?.let { readText("$it/${GoldenConfig.FILE_NAME}") }?.let(GoldenConfig::parse)
                },
                binaryStamp = fileStamp("/proc/self/exe").orEmpty(),
                // Still this build when the file on disk is replaced while the window is open.
                ownBinary = "/proc/${getpid()}/exe",
            )
        }

        internal fun executablePath(): String? = memScoped {
            val buffer = allocArray<ByteVar>(PATH_BYTES)
            val length = readlink("/proc/self/exe", buffer, (PATH_BYTES - 1).convert()).toInt()
            if (length <= 0) return@memScoped null
            buffer.toKString().take(length).removeSuffix(" (deleted)")
        }
    }
}
