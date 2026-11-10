plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "main"
                // GTK 4.22 from the host needs newer glibc symbols than Kotlin/Native's
                // bundled sysroot. Allow undefined symbols in shared libraries: they are
                // resolved at runtime against the system glibc (see README).
                linkerOpts("--allow-shlib-undefined")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":shell-ui"))
            implementation(project(":rust-core"))
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

// The executable must be relinked whenever the Rust static library changes; link tasks do
// not model that dependency on their own (see rust-core/build.gradle.kts).
val rustArchive = rootProject.layout.projectDirectory.file(
    "rust-core/target/x86_64-unknown-linux-gnu/debug/libkn_shell_core.a",
)
tasks.matching { it.name.startsWith("linkDebug") || it.name.startsWith("linkRelease") }.configureEach {
    if (this is Task) {
        inputs.file(rustArchive).optional()
    }
}
