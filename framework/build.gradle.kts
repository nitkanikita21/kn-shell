plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.dokka") version "2.0.0"
}

// GTK4 + layer-shell are consumed through a hand-written C interop instead of the
// archived `gtk-kn` project (see README "Deviations"): include paths come from
// pkg-config, linker options live in the .def file.
val gtkCflags: List<String> = providers.exec {
    commandLine("pkg-config", "--cflags", "gtk4", "gtk4-layer-shell-0")
}.standardOutput.asText.get().trim().split(Regex("\\s+")).filter { it.isNotBlank() }

val epoxyCflags: List<String> = providers.exec {
    commandLine("pkg-config", "--cflags", "epoxy")
}.standardOutput.asText.get().trim().split(Regex("\\s+")).filter { it.isNotBlank() }

kotlin {
    linuxX64 {
        // The host GTK 4.22 needs newer glibc symbols than Kotlin/Native's bundled sysroot;
        // test binaries resolve them at runtime like the app does (see shell-app).
        binaries.all { linkerOpts("--allow-shlib-undefined") }

        compilations.getByName("main").cinterops.create("gtk4") {
            defFile(project.file("src/nativeInterop/cinterop/gtk4.def"))
            compilerOpts(gtkCflags)
        }

        compilations.getByName("main").cinterops.create("gl") {
            defFile(project.file("src/nativeInterop/cinterop/gl.def"))
            compilerOpts(epoxyCflags)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }

        nativeMain.dependencies {
            implementation(project(":rust-core"))
        }

        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
