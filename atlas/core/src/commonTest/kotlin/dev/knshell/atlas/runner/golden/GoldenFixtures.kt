@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package dev.knshell.atlas.runner.golden

import dev.knshell.atlas.sampleGroup
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.mkdtemp
import platform.posix.strcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Two 2x2 pictures that differ in every pixel. */
val redPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR42mP4z8AARAwQCgAf7gP9Y167WwAAAABJRU5ErkJggg==",
)
val bluePng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAD0lEQVR42mNgYPgPRmAKABf2A/3HkIu0AAAAAElFTkSuQmCC",
)

val sampleEnvironment = RenderEnvironment(listOf("gtk" to "4.24.1", "renderer" to "GskCairoRenderer"))

/** A fresh directory with `goldens` and `out` inside, and a way to run a session over it. */
class Workspace {
    private val root: String = memScoped {
        val template = allocArray<ByteVar>(64)
        strcpy(template, "/tmp/atlas-golden-test-XXXXXX")
        checkNotNull(mkdtemp(template)) { "cannot make a temporary directory" }.toKString()
    }
    val goldens = "$root/goldens"
    val output = "$root/out"
    val cases = goldenCases(listOf(sampleGroup()))
    val log = mutableListOf<String>()

    fun run(
        record: Boolean = false,
        filter: String? = null,
        environment: RenderEnvironment = sampleEnvironment,
        binary: String = "build-1",
        capture: (GoldenCase) -> ByteArray = { redPng },
    ): GoldenReport = runBlocking {
        GoldenSession(
            options = GoldenOptions(goldens, output, record, filter),
            cases = goldenCases(listOf(sampleGroup()), filter),
            binary = binary,
            capture = { CaseShots(capture(it)) },
            environment = { environment },
            log = { log += it },
        ).run()
    }

    /** A session over any cases, with the pictures given whole. */
    fun runSession(cases: List<GoldenCase>, record: Boolean, capture: (GoldenCase) -> CaseShots): GoldenReport =
        runBlocking {
            GoldenSession(
                options = GoldenOptions(goldens, output, record),
                cases = cases,
                binary = "build-1",
                capture = { capture(it) },
                environment = { sampleEnvironment },
                log = { log += it },
            ).run()
        }

    fun golden(case: GoldenCase): ByteArray? = readBytes("$goldens/${case.path}")
}
