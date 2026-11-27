pluginManagement {
    includeBuild("icons-plugin")
    includeBuild("atlas/plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kn-shell"

include(":rust-core")
include(":palette-core")
include(":framework")
include(":icons:core")
include(":icons-packs:lucide")
include(":demo:shell-app")
include(":demo:shell-ui-kit")
include(":atlas:core")
include(":atlas:mcp")
include(":headless-compositor")
