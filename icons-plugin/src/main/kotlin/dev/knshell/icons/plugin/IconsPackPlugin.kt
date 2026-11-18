package dev.knshell.icons.plugin

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.kotlin.dsl.*

/**
 * Wires one upstream SVG icon set into a KN-shell icon pack module.
 *
 * ```kotlin
 * plugins { kotlin("multiplatform"); id("dev.knshell.icons.pack") }
 * iconsPack {
 *     objectName.set("Lucide")
 *     packageName.set("dev.knshell.icons.lucide")
 *     npmPackage.set("lucide-static")
 *     version.set("1.74.0")
 * }
 * ```
 *
 * Creates `syncIcons` (download + cache, skipped when warm) and `generateIcons`
 * (catalog + tree-shaken data), hooks the generated source directory into the
 * Kotlin/Native `nativeMain` compilation, and makes the compile task depend on the
 * generator. All Kotlin-plugin interaction is reflective so this build needs no
 * kotlin-gradle-plugin dependency.
 */
class IconsPackPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val ext = extensions.create<IconsPackExtension>("iconsPack")

        // Repo-root defaults (the directory holding settings.gradle.kts), so a pack
        // module can scan sibling modules without hard-coding "../".
        ext.scanBaseDir.convention(layout.projectDirectory.dir(repoRoot(target).absolutePath))
        ext.cacheDir.convention(ext.scanBaseDir.map { d -> d.dir(".cache/icons") })

        val syncProvider = tasks.register<SyncIconsTask>("syncIcons") {
            packLabel.set(provider { ext.npmPackage.orNull ?: ext.objectName.orNull ?: "" })
            version.set(ext.version)
            tarballUrl.set(provider {
                val npm = ext.npmPackage.get()
                ext.tarballUrl.orNull ?: "https://registry.npmjs.org/$npm/-/$npm-${ext.version.get()}.tgz"
            })
            localSourceDir.set(provider { ext.sourceDir.orNull?.asFile?.absolutePath })
            iconsSubdir.set(ext.iconsDir)
            cacheRoot.set(provider {
                val label = "${ext.npmPackage.orNull ?: ext.objectName.get()}-${ext.version.get()}"
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                layout.projectDirectory.dir(File(ext.cacheDir.get().asFile, label).absolutePath)
            })
        }

        val generateProvider = tasks.register<GenerateIconsTask>("generateIcons") {
            dependsOn(syncProvider)
            objectName.set(ext.objectName)
            packageName.set(ext.packageName)
            tintAttribute.set(ext.tintAttribute)
            strokeWidth.set(ext.defaultStrokeWidth)
            viewBoxSize.set(ext.defaultViewBoxSize)
        viewBoxOverride.set(ext.defaultViewBoxOverride)
            cachedIcons.set(provider { syncProvider.get().cacheRoot.get() })
            // Real file inputs (paths resolved lazily): codegen re-runs when a shell
            // starts using a new icon, which is what keeps tree-shaking honest.
            scanSources.from(
                ext.iconScanRoots.map { roots ->
                    val base = ext.scanBaseDir.get().asFile
                    roots.map { rel ->
                        fileTree(File(base, rel)).matching {
                            include("**/*.kt")
                            exclude("**/build/**")
                            exclude("**/.*/**")
                        }
                    }
                },
            )
            outputDir.set(layout.buildDirectory.dir("generated/iconPack/kotlin"))
        }

        // Generated sources must exist before the Kotlin/Native compilation reads
        // them, and nativeMain must know about the generated directory. Reflective:
        // no compile-time dependency on the Kotlin Gradle plugin.
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            afterEvaluate {
                val kotlin = extensions.getByName("kotlin")
                val srcOk = addGeneratedSourceDir(kotlin, generateProvider.get().outputDir.get().asFile, logger)
                if (!srcOk) {
                    logger.warn("[icons] $path: could not register the generated source dir via the kotlin extension")
                }
                val compile = tasks.findByName("compileKotlinLinuxX64")
                if (compile == null) {
                    logger.warn("[icons] $path: compileKotlinLinuxX64 not found — wire generateIcons manually")
                } else {
                    compile.dependsOn(generateProvider)
                }
            }
        }

        // Convenience aggregator: `./gradlew :icons-packs:lucide:icons` runs sync+generate.
        tasks.register("icons") {
            group = "icons"
            description = "Sync assets and regenerate the icon sources."
            dependsOn(generateProvider)
        }
        Unit
    }

    /** `kotlin.sourceSets["nativeMain"].kotlin.srcDir(dir)` via reflection. */
    private fun addGeneratedSourceDir(kotlinExt: Any, dir: File, log: Logger): Boolean = try {
        val sourceSets = kotlinExt.javaClass.getMethod("getSourceSets").invoke(kotlinExt)
        val nativeMain = sourceSets.javaClass.getMethod("getByName", String::class.java)
            .invoke(sourceSets, "nativeMain")
        val kotlinSourceSet = nativeMain.javaClass.getMethod("getKotlin").invoke(nativeMain)
        kotlinSourceSet.javaClass.getMethod("srcDir", Any::class.java).invoke(kotlinSourceSet, dir)
        log.info("[icons] nativeMain += $dir")
        true
    } catch (e: Exception) {
        log.info("[icons] source-dir registration failed: ${e.cause ?: e}")
        false
    }

    /** Nearest ancestor of the build root that holds a settings script = the repo root. */
    private fun repoRoot(project: Project): File {
        var dir: File? = project.rootDir
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        return project.rootDir
    }
}
