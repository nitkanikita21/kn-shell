// Tests are split by what they need. Classes named `*GraphicsTest` connect to a GTK display; the
// rest run anywhere. `linuxX64Test` runs the rest, `linuxX64GraphicsTest` runs the display ones,
// always under :headless-compositor: nothing appears on the desktop, and the tests can press keys.
// Apply after the `kotlin { }` block: `apply(from = rootProject.file("gradle/graphics-tests.gradle.kts"))`.

val graphicsTests = "*GraphicsTest.*"

tasks.named("linuxX64Test") {
    // This script does not see the Kotlin plugin's classes, hence the property by name.
    setProperty("args", listOf("--ktest_filter=*:-$graphicsTests"))
}

tasks.register<Exec>("linuxX64GraphicsTest") {
    group = "verification"
    description = "Runs the tests that need a GTK display (classes named *GraphicsTest)."
    dependsOn("linkDebugTestLinuxX64", ":headless-compositor:cargoBuild")
    commandLine(
        rootProject.file("headless-compositor/target/release/headless-compositor").absolutePath,
        "--",
        layout.buildDirectory.file("bin/linuxX64/debugTest/test.kexe").get().asFile.absolutePath,
        "--ktest_filter=$graphicsTests",
    )
}
