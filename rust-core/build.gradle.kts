import gobley.gradle.cargo.tasks.RustUpTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.gobley.rust)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
}

kotlin {
    linuxX64()
}

uniffi {
    generateFromLibrary {
        namespace = "kn_shell_core"
        packageName = "dev.knshell.core"
    }
}

// Point Gobley at the real Rust toolchain of this repository. The IDE launches Gradle
// with the system PATH (Arch `rustc`), whose version string cannot be parsed by Gobley;
// the repository-local rustup toolchain is used instead. Falls back to PATH if absent.
// Gobley prepends `toolchainDirectory` to PATH, so it must be the directory that
// actually contains the binaries (`<toolchain>/bin`), not the toolchain root.
val localToolchainBin: File? = rootProject.file(".rustup/toolchains")
    .listFiles()
    ?.map { File(it, "bin") }
    ?.firstOrNull { it.isDirectory && File(it, "rustc").exists() }

if (localToolchainBin != null) {
    rust {
        toolchainDirectory.set(localToolchainBin)
    }
}

// Gobley shells out to `rustup` when registering the Cargo target. The toolchain of this
// repository is local (`.cargo-home`), which is not on PATH when Gradle is started by an
// IDE (IntelliJ Sync) — point the tasks at the wrapper explicitly instead of relying on
// the launching shell environment.
val gobleyRustUp = rootProject.file("scripts/gobley-rustup.sh")
if (gobleyRustUp.exists()) {
    tasks.withType<RustUpTask>().configureEach {
        rustUp.set(gobleyRustUp)
    }
}

// Gobley's cargo tasks do not declare the Rust sources as Gradle inputs, so an edited
// `.rs` file leaves cargoBuild UP-TO-DATE and the stale static library ends up in the
// Kotlin/Native binary (silent: the app keeps running old service code).
// Declare the real inputs; cargo itself is incremental, so this is cheap.
tasks.matching { it.name.startsWith("cargoBuild") }.configureEach {
    if (this is Task) {
        inputs.dir(project.layout.projectDirectory.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(project.layout.projectDirectory.file("Cargo.toml"))
        inputs.file(project.layout.projectDirectory.file("Cargo.lock")).optional()
    }
}

// The Rust archive is included into the final binary through cinterop; make the archive
// an explicit input so a rebuilt library invalidates the cinterop klib and the link.
val rustDebugArchive = project.layout.buildDirectory.file(
    "../target/x86_64-unknown-linux-gnu/debug/libkn_shell_core.a",
)
tasks.matching { it.name.startsWith("cinteropRust") }.configureEach {
    if (this is Task) {
        inputs.file(rustDebugArchive).optional()
    }
}
