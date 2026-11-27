// The UI kit of the example shell: design system (theme), and components built on `framework`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    linuxX64 {
        // Test binaries resolve newer GTK/glibc symbols at runtime, like framework (see framework/build.gradle.kts).
        binaries.all { linkerOpts("--allow-shlib-undefined") }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":framework"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":icons-packs:lucide"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// `linuxX64Test` without a display, `linuxX64GraphicsTest` with one.
apply(from = rootProject.file("gradle/graphics-tests.gradle.kts"))
