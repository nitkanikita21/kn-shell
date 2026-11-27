import org.jetbrains.kotlin.gradle.plugin.mpp.Executable

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Каталог компонентів: сторіс у src/linuxX64Atlas/kotlin, таска runAtlas.
    id("dev.knshell.atlas")
}

val paletteArchiveFile =
    rootProject.file(
        "palette-core/target/x86_64-unknown-linux-gnu/debug/libpalette_core.a",
    )

kotlin {
    linuxX64 {
        val main = compilations["main"]

        main.cinterops {
            create("palette") {
                definitionFile = rootProject.file("palette-core/palette.def")
                compilerOpts("-I${rootProject.file("palette-core").absolutePath}")
            }
        }

        binaries {
            executable {
                entryPoint = "main"
            }

            // Спільне для всіх executable модуля, зокрема atlas від плагіна:
            // Rust/palette архів і glibc-прапорець.
            withType<Executable>().configureEach {
                linkerOpts("--allow-shlib-undefined")
                linkerOpts(paletteArchiveFile.absolutePath)
            }
        }
    }

    sourceSets {
        nativeMain {
            dependencies {
                implementation(project(":framework"))
                implementation(project(":demo:shell-ui-kit"))
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
    }
}

tasks.register("run") {
    group = "application"
    description = "Builds the shell (Rust + UniFFI + Kotlin/Native) and launches it."
    dependsOn("runReleaseExecutableLinuxX64")
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
