// Lets `desktop` build on its own (no Android SDK): gradle -p desktop hubJar
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.1.20" }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "desktop"
include(":core")
project(":core").projectDir = file("../core")
