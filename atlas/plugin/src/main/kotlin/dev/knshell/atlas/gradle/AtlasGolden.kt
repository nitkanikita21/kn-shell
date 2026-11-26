package dev.knshell.atlas.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import java.io.File
import java.security.MessageDigest

/** Name of the file beside the executable that tells the running atlas how to do a golden run. */
private const val CONFIG_FILE = "atlas-golden.properties"
private const val COMPOSITOR_PROJECT = ":headless-compositor"
private const val FONTS_PROPERTY = "atlas.goldenFonts"
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

/**
 * Writes the `key=value` file the atlas reads from beside its executable and, when the project pins
 * its fonts, the fontconfig file that shows a golden run those fonts only.
 *
 * `env.KN_ATLAS_GOLDEN_FONTS` records which fonts that is: a digest of the files, or `system`.
 */
abstract class WriteGoldenConfig : DefaultTask() {
    @get:Input
    abstract val entries: MapProperty<String, String>

    @get:Input
    @get:Optional
    abstract val fontsDirectory: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fontFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val file: RegularFileProperty

    @get:OutputFile
    abstract val fontsConfig: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = LinkedHashMap(entries.get())
        val directory = fontsDirectory.orNull?.let(::File)
        val config = fontsConfig.get().asFile
        if (directory == null) {
            config.writeText("<!-- no fonts directory: golden runs use the system fonts -->\n")
            lines["env.KN_ATLAS_GOLDEN_FONTS"] = "system"
        } else {
            val fonts = directory.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
                .sortedBy { it.relativeTo(directory).path }
                .toList()
            check(fonts.isNotEmpty()) { "no font files (${FONT_EXTENSIONS.joinToString()}) in $directory" }
            val digest = MessageDigest.getInstance("SHA-256")
            fonts.forEach { font ->
                digest.update(font.relativeTo(directory).path.toByteArray())
                digest.update(font.readBytes())
            }
            config.writeText(fontsConf(directory))
            lines["env.FONTCONFIG_FILE"] = config.absolutePath
            lines["env.KN_ATLAS_GOLDEN_FONTS"] = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        }
        file.get().asFile.writeText(lines.entries.joinToString("") { "${it.key}=${it.value}\n" })
    }

    // Only the directory and how glyphs are rasterised: which family stands in for which is left to
    // fontconfig, so that the pictures are not styled by the tool.
    private fun fontsConf(directory: File): String {
        val path = directory.absolutePath.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            |<?xml version="1.0"?>
            |<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
            |<fontconfig>
            |  <dir>$path</dir>
            |  <cachedir prefix="xdg">fontconfig</cachedir>
            |  <match target="font">
            |    <edit name="antialias" mode="assign"><bool>true</bool></edit>
            |    <edit name="hinting" mode="assign"><bool>true</bool></edit>
            |    <edit name="hintstyle" mode="assign"><const>hintslight</const></edit>
            |    <edit name="rgba" mode="assign"><const>none</const></edit>
            |    <edit name="embeddedbitmap" mode="assign"><bool>false</bool></edit>
            |  </match>
            |</fontconfig>
            |
        """.trimMargin()
    }
}

/**
 * The golden tasks of an atlas module:
 *
 *  - `atlasGoldenCheck` renders every story without a screen and compares it with its golden;
 *  - `atlasGoldenRecord` updates the goldens;
 *  - `-Pstory=Group/Entry` narrows either to one story, `-Pgoldens=<dir>` uses other goldens.
 *
 * The fonts are the project's: `atlas { goldenFonts = … }` or the Gradle property `atlas.goldenFonts`
 * (relative to the root) shows a run those fonts only; without either it sees the system fonts.
 *
 * The environment the pictures are drawn in is defined here only. It is written beside the
 * executable, where both these tasks and the check the atlas window starts itself read it.
 */
internal fun addGoldenTasks(project: Project, binary: Executable, extension: AtlasExtension) {
    val root = project.rootProject
    val ownCompositor = extension.compositor
    if (ownCompositor == null && root.findProject(COMPOSITOR_PROJECT) == null) {
        project.logger.info("dev.knshell.atlas: no $COMPOSITOR_PROJECT project, so no golden tasks")
        return
    }
    val compositor = ownCompositor ?: root.file("headless-compositor/target/release/headless-compositor")

    val goldens = project.providers.gradleProperty("goldens").orNull?.let { root.file(it) }
        ?: extension.goldenDir
        ?: project.file("atlas-goldens")
    val fonts = extension.goldenFonts
        ?: project.providers.gradleProperty(FONTS_PROPERTY).orNull?.let { root.file(it) }
    val build = project.layout.buildDirectory.get().asFile
    val output = File(build, "atlas-golden")
    val sandbox = File(build, "atlas-golden-env")

    // Everything that could change a pixel and comes from the machine is replaced.
    val environment = linkedMapOf(
        // Software rendering: the GPU and its driver play no part.
        "GSK_RENDERER" to "cairo",
        "GDK_SCALE" to "1",
        "GDK_DPI_SCALE" to "1",
        "GTK_THEME" to "Default",
        // No desktop settings: not through the portal, not from dconf, not from settings.ini.
        "GDK_DEBUG" to "no-portals",
        "GSETTINGS_BACKEND" to "memory",
        "DBUS_SESSION_BUS_ADDRESS" to "disabled:",
        "GTK_A11Y" to "none",
        "LC_ALL" to "C.UTF-8",
        "XDG_CONFIG_HOME" to File(sandbox, "config").absolutePath,
        "XDG_CACHE_HOME" to File(sandbox, "cache").absolutePath,
    )

    val configFile = File(binary.outputDirectory, CONFIG_FILE)
    val config = project.tasks.register("atlasGoldenConfig", WriteGoldenConfig::class.java) { task ->
        task.description = "Tells the atlas executable how to run its golden check."
        task.file.set(configFile)
        task.fontsConfig.set(File(sandbox, "fonts.conf"))
        if (fonts != null) {
            task.fontsDirectory.set(fonts.absolutePath)
            task.fontFiles.from(project.fileTree(fonts))
        }
        task.entries.put("compositor", compositor.absolutePath)
        task.entries.put("goldens", goldens.absolutePath)
        task.entries.put("output", output.absolutePath)
        environment.forEach { (key, value) -> task.entries.put("env.$key", value) }
    }
    // Whoever builds the atlas (an IDE, `atlas-mcp` calling the link task) gets the file too.
    binary.linkTaskProvider.configure { link ->
        link.finalizedBy(config)
        if (ownCompositor == null) link.dependsOn("$COMPOSITOR_PROJECT:cargoBuild")
    }

    val story = project.providers.gradleProperty("story").orNull
    fun register(name: String, record: Boolean, summary: String) {
        project.tasks.register(name, Exec::class.java) { task ->
            task.group = "verification"
            task.description = summary
            task.dependsOn(binary.linkTaskProvider, config)
            task.workingDir = project.projectDir
            val command = mutableListOf(
                compositor.absolutePath,
                "--",
                binary.outputFile.absolutePath,
                "--golden=${goldens.absolutePath}",
                "--golden-out=${output.absolutePath}",
            )
            if (record) command += "--record"
            if (story != null) command += "--story=$story"
            task.commandLine(command)
            task.doFirst {
                configFile.readLines()
                    .filter { it.startsWith("env.") }
                    .forEach { line ->
                        task.environment(line.removePrefix("env.").substringBefore('='), line.substringAfter('='))
                    }
            }
        }
    }
    register("atlasGoldenCheck", record = false, "Renders every story without a screen and compares it with its golden.")
    register("atlasGoldenRecord", record = true, "Renders every story without a screen and updates the goldens.")
}
