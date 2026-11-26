pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        // Один каталог версій на весь репозиторій: плагін компілюється проти того самого KGP.
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "atlas-plugin"
