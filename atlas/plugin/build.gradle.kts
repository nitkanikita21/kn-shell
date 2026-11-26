plugins {
    // Не `kotlin-dsl`: вбудований у Gradle компілятор не читає метадані Kotlin Gradle plugin 2.x.
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

dependencies {
    // compileOnly: у збірці-споживачі KGP уже є, плагін працює з тими самими класами.
    compileOnly(libs.kotlin.gradle.plugin)
}

kotlin { jvmToolchain(21) }

gradlePlugin {
    plugins {
        create("atlas") {
            id = "dev.knshell.atlas"
            implementationClass = "dev.knshell.atlas.gradle.AtlasPlugin"
            displayName = "KN-shell Atlas"
            description = "Adds the Atlas component catalog (compilation, executable and run tasks) to a linuxX64 module."
        }
    }
}
