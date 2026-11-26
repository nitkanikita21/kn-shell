package dev.knshell.atlas.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

/**
 * Adds the Atlas to a Kotlin Multiplatform module with a `linuxX64` target:
 *
 *  - the compilation `atlas` (sources in `src/linuxX64Atlas/kotlin`), which sees the internals of
 *    `main` and keeps the stories out of the application binary;
 *  - the debug executable `atlas`, and with it `linkAtlasDebugExecutableLinuxX64` and
 *    `runAtlasDebugExecutableLinuxX64` (the `atlas-mcp` tool finds atlases by that run task);
 *  - the task `runAtlas`; `-Pstory=Group/Entry` opens that story;
 *  - the tasks `atlasGoldenCheck` and `atlasGoldenRecord` (see [addGoldenTasks]).
 *
 * Linker options the module's own libraries need belong to the module: configure them for every
 * executable (`binaries.withType<Executable>().configureEach { … }`) and the atlas gets them too.
 */
class AtlasPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("atlas", AtlasExtension::class.java)

        val targets = mutableListOf<KotlinNativeTarget>()

        project.pluginManager.withPlugin(MULTIPLATFORM_PLUGIN) {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            // `configureEach` runs as `linuxX64 { }` creates the target, so the compilation and
            // the executable exist before the build script's own block touches them.
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
                if (target.konanTarget.name == LINUX_X64) {
                    addAtlas(target)
                    targets += target
                }
            }
        }
        // The build script configures the extension only after this plugin was applied.
        project.afterEvaluate {
            if (targets.isEmpty()) {
                project.logger.warn(
                    "dev.knshell.atlas: ${project.path} has no linuxX64 target of $MULTIPLATFORM_PLUGIN, " +
                        "so no atlas was added",
                )
            }
            targets.forEach { configure(project, it, extension) }
            // One atlas per module: the golden tasks belong to the first linuxX64 target.
            targets.firstOrNull()?.let {
                addGoldenTasks(project, it.binaries.getExecutable("atlas", NativeBuildType.DEBUG), extension)
            }
        }
        addRunTasks(project)
    }

    private fun addAtlas(target: KotlinNativeTarget) {
        val atlas: KotlinNativeCompilation = target.compilations.create("atlas")
        atlas.associateWith(target.compilations.getByName("main"))

        target.binaries.executable("atlas", listOf(NativeBuildType.DEBUG)) {
            compilation = atlas
            // GTK from the host needs newer glibc symbols than Kotlin/Native's sysroot has.
            linkerOpts("--allow-shlib-undefined")
        }
    }

    private fun configure(project: Project, target: KotlinNativeTarget, extension: AtlasExtension) {
        target.binaries.getExecutable("atlas", NativeBuildType.DEBUG).entryPoint = extension.entryPoint

        val core = extension.core ?: project.rootProject.findProject(DEFAULT_CORE)?.let {
            project.dependencies.project(mapOf("path" to DEFAULT_CORE))
        }
        if (core == null) {
            project.logger.warn("dev.knshell.atlas: no $DEFAULT_CORE project; set `atlas { core = … }`")
        } else {
            val atlas = target.compilations.getByName("atlas")
            project.dependencies.add(atlas.defaultSourceSet.implementationConfigurationName, core)
        }
    }

    private fun addRunTasks(project: Project) {
        project.tasks.register("runAtlas") {
            it.group = "application"
            it.description = "Builds and launches the Atlas component catalog (debug)."
            it.dependsOn(RUN_TASK)
        }
        // The Kotlin run task has no `--args`.
        val story = project.providers.gradleProperty("story")
        project.tasks.withType(Exec::class.java).matching { it.name == RUN_TASK }.configureEach { task ->
            story.orNull?.let { task.args("--story", it) }
        }
    }

    private companion object {
        const val MULTIPLATFORM_PLUGIN = "org.jetbrains.kotlin.multiplatform"
        const val LINUX_X64 = "linux_x64"
        const val DEFAULT_CORE = ":atlas:core"
        const val RUN_TASK = "runAtlasDebugExecutableLinuxX64"
    }
}
