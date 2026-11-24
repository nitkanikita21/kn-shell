import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBinary
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val paletteArchiveFile =
    rootProject.file(
        "palette-core/target/x86_64-unknown-linux-gnu/debug/libpalette_core.a",
    )

// ATLAS: спільні linker-опції для обох виконуваних файлів (app і atlas),
// щоб Rust/palette архіви та glibc-прапорець не дублювати.
fun NativeBinary.applyNativeLinking() {
    linkerOpts("--allow-shlib-undefined")
    linkerOpts(paletteArchiveFile.absolutePath)
}

kotlin {
    linuxX64 {
        val main = compilations["main"]

        main.cinterops {
            create("palette") {
                definitionFile = rootProject.file("palette-core/palette.def")
                compilerOpts("-I${rootProject.file("palette-core").absolutePath}")
            }
        }

        val atlas = compilations.create("atlas") {
            associateWith(main)
        }

        binaries {
            executable {
                entryPoint = "main"
                applyNativeLinking()
            }

            // Таска: linkAtlasDebugExecutableLinuxX64
            executable("atlas", listOf(DEBUG)) {
                compilation = atlas
                entryPoint = "atlas.main"
                applyNativeLinking()
            }
        }
    }

    sourceSets {
        nativeMain {
            dependencies {
                implementation(project(":framework"))
                implementation(project(":icons-packs:lucide"))
                implementation(project(":rust-core"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kache)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.koin.core)
            }
        }

        // ATLAS: залежності лише для сторіс. Каталог: src/linuxX64Atlas/kotlin
        getByName("linuxX64Atlas") {
            dependencies {
                implementation(project(":atlas-core"))
            }
        }
    }
}

tasks.register("run") {
    group = "application"
    description = "Builds the shell (Rust + UniFFI + Kotlin/Native) and launches it."
    dependsOn("runReleaseExecutableLinuxX64")
}

tasks.register("runAtlas") {
    group = "application"
    description = "Builds and launches the Atlas component catalog (debug)."
    dependsOn("runAtlasDebugExecutableLinuxX64")
}

val rustArchive =
    rootProject.layout.projectDirectory.file(
        "rust-core/target/x86_64-unknown-linux-gnu/debug/libkn_shell_core.a",
    )

// Умова ширша за "linkDebug", бо `linkAtlasDebugExecutableLinuxX64` з нього не починається.
tasks.matching { it.name.startsWith("link") && it.name.contains("Executable") }.configureEach {
    inputs.file(rustArchive).optional()
    inputs.file(paletteArchiveFile).optional()
    dependsOn(project(":palette-core").tasks.matching { it.name.startsWith("cargoBuild") })
}
