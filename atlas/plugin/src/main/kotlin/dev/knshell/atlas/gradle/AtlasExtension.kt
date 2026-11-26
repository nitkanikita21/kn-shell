package dev.knshell.atlas.gradle

import java.io.File

/**
 * Configuration of the `dev.knshell.atlas` plugin.
 *
 * ```kotlin
 * atlas {
 *     entryPoint = "atlas.main"          // default
 *     core = project(":atlas:core")      // default when such a project exists
 * }
 * ```
 */
open class AtlasExtension {
    /** Fully qualified `main` of the catalog, declared in `src/linuxX64Atlas/kotlin`. */
    var entryPoint: String = "atlas.main"

    /**
     * The dependency that provides the atlas DSL and runner, in any notation `implementation()`
     * accepts. When unset, the project `:atlas:core` of this build is used if there is one.
     */
    var core: Any? = null

    /** Where the golden pictures of the module live; `<module>/atlas-goldens` when unset. */
    var goldenDir: File? = null

    /** The `headless-compositor` binary golden runs render under; that of `:headless-compositor` when unset. */
    var compositor: File? = null

    /**
     * The fonts golden pictures are drawn with, and no others. When unset, the Gradle property
     * `atlas.goldenFonts` is used; without it, the system fonts.
     */
    var goldenFonts: File? = null
}
