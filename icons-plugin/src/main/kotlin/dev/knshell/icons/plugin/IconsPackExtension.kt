package dev.knshell.icons.plugin

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Declarative description of one upstream icon pack.
 *
 * A pack module says *what* its assets are; all mechanics (download, cache,
 * usage scan, catalog + tree-shaken data codegen) live in [IconsPackPlugin].
 *
 * ```kotlin
 * iconsPack {
 *     objectName.set("Lucide")
 *     packageName.set("dev.knshell.icons.lucide")
 *     version.set("0.544.0")
 *     iconsDir.set("package/icons")
 *     tintAttribute.set("stroke")
 *     defaultStrokeWidth.set(2.0)
 * }
 * ```
 */
abstract class IconsPackExtension @Inject constructor(objects: ObjectFactory) {

    /** Generated `object <objectName>` with one typed `IconRef` per catalog entry. */
    val objectName: Property<String> = objects.property(String::class.java)

    /** Kotlin package of the generated files (usually the pack module's own package). */
    val packageName: Property<String> = objects.property(String::class.java)

    /**
     * Upstream asset version — becomes part of the cache directory name, so bumping
     * it re-syncs cleanly. Defaults to "latest" for local-dir sources.
     */
    val version: Property<String> = objects.property(String::class.java).convention("latest")

    /** npm package whose tarball carries the SVGs (mutually exclusive with [sourceDir]). */
    val npmPackage: Property<String> = objects.property(String::class.java)

    /** Explicit tarball URL override; when unset it is derived from [npmPackage] + [version]. */
    val tarballUrl: Property<String> = objects.property(String::class.java)

    /** Directory inside the unpacked tarball (or [sourceDir]) that holds `*.svg` files. */
    val iconsDir: Property<String> = objects.property(String::class.java).convention("icons")

    /** Alternative to downloading: a checked-out/local directory containing the SVGs. */
    val sourceDir: org.gradle.api.file.DirectoryProperty = objects.directoryProperty()

    /** `stroke` (lucide/tabler/feather) or `fill` (filled packs) — passed into `IconStyle`. */
    val tintAttribute: Property<String> = objects.property(String::class.java).convention("stroke")

    /** Icon geometry metadata for `IconStyle` — passed verbatim into the generated file header docs. */
    val defaultStrokeWidth: Property<Double> = objects.property(Double::class.java).convention(2.0)
    val defaultViewBoxSize: Property<Double> = objects.property(Double::class.java).convention(24.0)

    /**
     * Full `viewBox` for packs whose canvas is not `0 0 S S` (Material Symbols:
     * `0 -960 960 960`). Unset means the box is derived from [defaultViewBoxSize].
     */
    val defaultViewBoxOverride: Property<String> = objects.property(String::class.java)

    /** Source roots scanned for `Icon` usages (file paths, NOT project deps — avoids cycles). */
    val iconScanRoots: SetProperty<String> = objects.setProperty(String::class.java)
        .convention(setOf("framework/src", "shell-app/src", "icons-packs"))

    /** Repo root the scan roots are resolved against. */
    val scanBaseDir: org.gradle.api.file.DirectoryProperty = objects.directoryProperty()

    /** Where downloaded/extracted assets are cached (gitignored, key = name+version). */
    val cacheDir: org.gradle.api.file.DirectoryProperty = objects.directoryProperty()
}
