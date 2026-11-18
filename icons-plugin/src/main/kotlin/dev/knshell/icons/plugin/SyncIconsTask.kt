package dev.knshell.icons.plugin

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Fetches the pack's icon set into the local cache directory: either downloads
 * the pack's npm tarball, or copies from a checked-out local `sourceDir`.
 *
 * The cache holds ONLY the flat `<id>.svg` files under `<cacheRoot>/icons` plus
 * a version marker, so re-runs are free and fully offline. Pack layout inside
 * the archive is discovered automatically (any directory whose files are
 * dominated by `.svg`), which is what makes third-party packs with a different
 * internal structure work without extra configuration.
 */
abstract class SyncIconsTask : DefaultTask() {

    @get:Input val packLabel: Property<String> = project.objects.property(String::class.java)
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Internal val tarballUrl: Property<String> = project.objects.property(String::class.java)
    /**
     * Icon directory inside the archive, e.g. `icons` (npm: `package/icons`) or
     * `outlined` for Material Symbols. Used as the preferred location after
     * extraction; when it does not exist the icon dir is discovered by svg
     * density, so simple packs need no configuration at all.
     */
    @get:Input val iconsSubdir: Property<String> = project.objects.property(String::class.java)
    /** Set instead of [tarballUrl] to sync from a checked-out local directory. */
    @get:Internal val localSourceDir: Property<String> = project.objects.property(String::class.java)
    /** Cache root for this pack+version; icons end up under `<cacheRoot>/icons`. */
    @get:OutputDirectory val cacheRoot: DirectoryProperty = project.objects.directoryProperty()

    init {
        group = "icons"
        description = "Fetch icon pack assets into the local cache."
        notCompatibleWithConfigurationCache("downloads over the network")
    }

    @TaskAction
    fun sync() {
        val target = cacheRoot.get().asFile
        val svgDir = File(target, "icons")
        val marker = File(target, ".synced-version")

        // A local directory source needs no download; sourceDir points at the
        // directory that directly holds the `<id>.svg` files.
        val local = localSourceDir.orNull
        if (local != null) {
            if (readMarker(marker) == "local:$local") return
            clearIcons(svgDir)
            target.mkdirs()
            File(local).copyRecursively(svgDir, overwrite = true)
            marker.writeText("local:$local")
            report(svgDir, "synced from local dir $local")
            return
        }

        if (readMarker(marker) == version.get()) {
            logger.info("[icons] ${packLabel.get()} ${version.get()}: cache warm, skipping sync")
            return
        }

        val url = tarballUrl.get()
        logger.lifecycle("[icons] downloading $url")
        val tgz = File.createTempFile("icons-", ".tgz")
        try {
            java.net.URI(url).toURL().openStream().use { input -> tgz.writeBytes(input.readBytes()) }
            clearIcons(svgDir)
            target.mkdirs()
            // Extract the whole archive with its single top-level directory
            // removed, then locate the icon dir in the result. Per-member glob
            // patterns combined with a partial --strip-components are silently
            // unreliable in GNU tar 1.35, and a strip value larger than a
            // member's depth makes tar SKIP that member (exit 0, nothing
            // extracted) — whole-archive extraction is the dependable form.
            // Discovering the svg dir afterwards also normalizes whatever
            // layout a pack uses (package/, dist/, bare icons/, ...).
            val proc = ProcessBuilder(
                "tar", "-xzf", tgz.absolutePath,
                "--strip-components", "1", "-C", target.absolutePath,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) {
                throw GradleException("[icons] failed to extract $url into $target: ${out.take(500)}")
            }
            val wanted = iconsSubdir.get().trim('/').removePrefix("package/").ifEmpty { "icons" }
            val source = preferredIconDir(target, wanted) ?: findIconDir(target)
                ?: throw GradleException(
                    "[icons] $url: no directory with at least 8 svg files found in the tarball " +
                        "(extraction target: $target)"
                )
            if (source != svgDir) {
                clearIcons(svgDir)
                if (!source.renameTo(svgDir)) {
                    source.copyRecursively(svgDir, overwrite = true)
                    source.deleteRecursively()
                }
            }
            // Extraction leaves the pack's other files next to the icons; the
            // cache directory should hold only the icon set and its marker.
            target.listFiles()?.forEach { f ->
                if (f != svgDir && f.name != ".synced-version") f.deleteRecursively()
            }
            marker.writeText(version.get())
            report(svgDir, "downloaded ${packLabel.get()} ${version.get()}")
        } finally {
            tgz.delete()
        }
    }

    /**
     * The configured [wanted] directory ([iconsSubdir], normalized through the
     * archive's single stripped top level), if it holds a usable icon set.
     */
    private fun preferredIconDir(root: File, wanted: String): File? {
        var dir = root
        for (part in wanted.split('/')) {
            if (part.isEmpty()) continue
            dir = File(dir, part)
            if (!dir.isDirectory) return null
        }
        val svgs = dir.listFiles()?.count { f -> f.isFile && f.name.endsWith(".svg") } ?: 0
        return if (svgs >= 8) dir else null
    }

    /**
     * The directory under [root] holding the most `.svg` files — at least
     * [minSvgs] of them — i.e. the icon set of an arbitrary pack tarball.
     * Duplicated sets at several depths (npm lucide has `icons/` and
     * `dist/esm/icons/`) resolve to the full set because only it can win on
     * count.
     */
    private fun findIconDir(root: File, minSvgs: Int = 8): File? {
        var best: File? = null
        var bestCount = 0
        root.walkTopDown()
            .filter { it.isDirectory }
            .forEach { dir ->
                val svgs = dir.listFiles()?.count { f -> f.isFile && f.name.endsWith(".svg") } ?: 0
                if (svgs > bestCount) {
                    bestCount = svgs
                    best = dir
                }
            }
        return if (bestCount >= minSvgs) best else null
    }

    private fun readMarker(marker: File): String? = if (marker.exists()) marker.readText().trim() else null

    private fun clearIcons(svgDir: File) {
        if (svgDir.exists()) svgDir.deleteRecursively()
    }

    private fun report(svgDir: File, what: String) {
        val count = svgDir.listFiles()?.count { f -> f.isFile && f.name.endsWith(".svg") } ?: 0
        if (count == 0) throw GradleException("[icons] ${packLabel.get()}: $what — no svg files in $svgDir")
        logger.lifecycle("[icons] ${packLabel.get()}: $what — $count svg files in ${cacheRoot.get().asFile}")
    }
}
