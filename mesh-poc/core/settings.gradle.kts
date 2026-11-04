// Lets `core` build standalone (no Android SDK) with: gradle -p core test
// Ignored when built as part of the root project.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.1.20" }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
