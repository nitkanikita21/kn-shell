package dev.knshell.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.posix.usleep

/** Needs no display and no main loop. */
class ChildProcessTest {

    private fun ChildProcess.await(): Int? {
        val started = TimeSource.Monotonic.markNow()
        while (isRunning && started.elapsedNow() < 10.seconds) usleep(10_000u)
        return exitStatus.also { close() }
    }

    @Test
    fun `the exit status and the added environment arrive`() {
        val process = ChildProcess.start(listOf("sh", "-c", "exit \$KN_STATUS"), mapOf("KN_STATUS" to "7"))

        assertEquals(7, process.await())
    }

    @Test
    fun `a running process has no status and can be stopped`() {
        val process = ChildProcess.start(listOf("sh", "-c", "sleep 30"))
        assertTrue(process.isRunning)
        assertEquals(null, process.exitStatus)

        process.stop()

        assertEquals(-1, process.await())
    }

    @Test
    fun `a program that does not exist is reported`() {
        assertFailsWith<IllegalStateException> { ChildProcess.start(listOf("/nonexistent/program")) }
    }
}
