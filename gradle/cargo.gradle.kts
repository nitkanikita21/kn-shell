// A Gradle module around a host Rust crate: a tool that runs on the build machine and is not linked
// into any Kotlin binary (those go through Gobley, see rust-core). Apply from the module's build
// script: `apply(from = rootProject.file("gradle/cargo.gradle.kts"))`.
//
//   cargoBuild    release build; part of `assemble`
//   cargoTest     `cargo test`; part of `check`
//
// Cargo decides what is up to date, so the tasks always run it.

apply(plugin = "base")

// The repository's own toolchain when the bootstrap installed one (as in rust-core), else `PATH`.
val localToolchainBin: File? = rootProject.file(".rustup/toolchains")
    .listFiles()
    ?.map { File(it, "bin") }
    ?.firstOrNull { File(it, "cargo").exists() }

fun registerCargo(name: String, group: String, description: String, vararg arguments: String) =
    tasks.register<Exec>(name) {
        this.group = group
        this.description = description
        workingDir = projectDir
        val cargo = localToolchainBin?.let { File(it, "cargo").absolutePath } ?: "cargo"
        localToolchainBin?.let { environment("PATH", "${it.absolutePath}:${System.getenv("PATH")}") }
        commandLine(cargo, *arguments)
    }

val cargoBuild = registerCargo("cargoBuild", "build", "Builds the crate in release mode.", "build", "--release")
val cargoTest = registerCargo("cargoTest", "verification", "Runs the tests of the crate.", "test")

tasks.named("assemble") { dependsOn(cargoBuild) }
tasks.named("check") { dependsOn(cargoTest) }
