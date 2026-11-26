package dev.knshell.atlas.runner.golden

/**
 * How this atlas runs its own golden check, as the Gradle plugin wrote it beside the executable
 * (`atlas-golden.properties`).
 */
data class GoldenConfig(
    val compositor: String,
    val goldens: String,
    val output: String,
    /** The environment the pictures are drawn in. */
    val environment: Map<String, String>,
) {
    /** The check of the atlas executable [binary], on a private display without a screen. */
    fun command(binary: String): List<String> =
        listOf(compositor, "--", binary, "--golden=$goldens", "--golden-out=$output")

    companion object {
        const val FILE_NAME = "atlas-golden.properties"

        /** `key=value` lines; `env.X=…` are the environment. `null` when a required key is missing. */
        fun parse(text: String): GoldenConfig? {
            val entries = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return GoldenConfig(
                compositor = entries["compositor"] ?: return null,
                goldens = entries["goldens"] ?: return null,
                output = entries["output"] ?: return null,
                environment = entries.filterKeys { it.startsWith("env.") }.mapKeys { it.key.removePrefix("env.") },
            )
        }
    }
}
