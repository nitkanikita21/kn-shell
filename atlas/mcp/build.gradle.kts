// The `atlas-mcp` daemon: a host Rust crate. It depends on :headless-compositor through Cargo.
apply(from = rootProject.file("gradle/cargo.gradle.kts"))

// `atlas-mcp` on PATH (~/.cargo/bin), which is what the MCP client runs.
tasks.register<Exec>("cargoInstall") {
    group = "distribution"
    description = "Installs atlas-mcp into the Cargo bin directory."
    workingDir = projectDir
    commandLine("cargo", "install", "--path", ".")
}
