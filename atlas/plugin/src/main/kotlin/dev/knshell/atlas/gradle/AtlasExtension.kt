package dev.knshell.atlas.gradle

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
}
