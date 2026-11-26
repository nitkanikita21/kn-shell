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
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(project(":icons-packs:lucide"))

                api(project(":framework"))
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
