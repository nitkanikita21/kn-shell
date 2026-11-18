plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// librsvg is consumed through a hand-written C interop, exactly like the
// framework consumes GTK4 (see framework/src/nativeInterop/cinterop/gtk4.def):
// include paths come from pkg-config, linker options live in the .def file.
val rsvgCflags: List<String> = providers.exec {
    commandLine("pkg-config", "--cflags", "librsvg-2.0", "gtk4")
}.standardOutput.asText.get().trim().split(Regex("\\s+")).filter { it.isNotBlank() }

kotlin {
    linuxX64 {
        // Same host-glibc caveat as the framework: system GTK 4.22 needs newer
        // symbols than the bundled sysroot; test binaries resolve them at runtime.
        binaries.all { linkerOpts("--allow-shlib-undefined") }

        compilations.getByName("main").cinterops.create("rsvg") {
            defFile(project.file("src/nativeInterop/cinterop/rsvg.def"))
            compilerOpts(rsvgCflags)
        }
    }

    sourceSets {
        nativeMain.dependencies {
            // Exposed api: Icon's signature mentions framework types (Color, Modifier,
            // the GTK node base class), so every consumer needs them on its compile path.
            api(project(":framework"))
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
