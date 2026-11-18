plugins { `kotlin-dsl` }

repositories { mavenCentral(); gradlePluginPortal() }

// The plugin generates SVG data tables whose entries can exceed the 64KB JVM
// bytecode limit when compiled from string literals; keep the generator honest
// (it emits chunked string concatenations) and let it run with JDK default
// target bytecode.
kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("iconsPack") {
            id = "dev.knshell.icons.pack"
            implementationClass = "dev.knshell.icons.plugin.IconsPackPlugin"
            displayName = "KN-shell icon pack generator"
            description = "Syncs an upstream SVG icon set and generates a catalog + tree-shaken data module for :icons:core."
        }
    }
}
