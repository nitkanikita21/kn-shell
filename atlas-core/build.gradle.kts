plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                // DSL (atlas.dsl): тільки Compose runtime, без UI
                implementation(project(":framework"))
                implementation(libs.kotlinx.coroutines.core)

                // Viewer (atlas.viewer): твої GTK-примітиви
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