plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Gobley writes palette-core's static archive into the crate's own `target/` (same
// layout it uses for rust-core). Link it into the executable below.
val paletteArchiveFile =
    rootProject.file(
        "palette-core/target/x86_64-unknown-linux-gnu/debug/libpalette_core.a",
    )

kotlin {
    linuxX64 {
        compilations["main"].cinterops {
            // Bind palette-core's hand-written C ABI (`palette_core.h`) into Kotlin.
            // cinterop does not add the `.def` directory to the header search path, so
            // point it at the crate root explicitly via `-I`. The native archive is
            // linked by the executable (see `binaries` below) because cinterop itself
            // does not accept `linkerOpts`.
            create("palette") {
                definitionFile = rootProject.file("palette-core/palette.def")
                compilerOpts("-I${rootProject.file("palette-core").absolutePath}")
            }
        }

        binaries {
            executable {
                entryPoint = "main"
                // GTK 4.22 from the host needs newer glibc symbols than Kotlin/Native's
                // bundled sysroot. Allow undefined symbols in shared libraries: they are
                // resolved at runtime against the system glibc (see README).
                linkerOpts("--allow-shlib-undefined")
                // Link palette-core's self-contained static archive into the executable.
                // (Its Rust dependencies are bundled into the `.a` by the staticlib
                // crate-type, so no extra native libs are needed here.)
                linkerOpts(paletteArchiveFile.absolutePath)
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
    }
}

// `./gradlew run` — the single entry point promised by the specification:
// it compiles Rust (release), generates the UniFFI bindings, links the
// Kotlin/Native executable and launches the shell.
tasks.register("run") {
    group = "application"
    description = "Builds the shell (Rust + UniFFI + Kotlin/Native) and launches it."
    dependsOn("runReleaseExecutableLinuxX64")
}

// The executable must be relinked whenever a Rust static library changes; link tasks do
// not model that dependency on their own (see rust-core/build.gradle.kts and below).
val rustArchive =
    rootProject.layout.projectDirectory.file(
        "rust-core/target/x86_64-unknown-linux-gnu/debug/libkn_shell_core.a",
    )
tasks.matching { it.name.startsWith("linkDebug") || it.name.startsWith("linkRelease") }.configureEach {
    if (this is Task) {
        inputs.file(rustArchive).optional()
        inputs.file(paletteArchiveFile).optional()
        // Ensure the palette-core archive exists before linking the executable.
        dependsOn(project(":palette-core").tasks.matching { it.name.startsWith("cargoBuild") })
    }
}
