// Root build script.
// All Kotlin/Rust/Gobley plugin versions live in gradle/libs.versions.toml.
plugins {
    // Тут (а не лише в модулі), щоб плагін атласу й Kotlin plugin мали спільний classloader.
    id("dev.knshell.atlas") apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.atomicfu) apply false
    alias(libs.plugins.gobley.rust) apply false
    alias(libs.plugins.gobley.cargo) apply false
    alias(libs.plugins.gobley.uniffi) apply false
}
