plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.atomicfu)
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
