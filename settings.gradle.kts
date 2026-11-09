pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // JitPack, limité à la bibliothèque officielle C2PA (Content Authenticity Initiative).
        maven("https://jitpack.io") { content { includeGroup("com.github.contentauth") } }
    }
}
rootProject.name = "ma-cam"
include(":app")
