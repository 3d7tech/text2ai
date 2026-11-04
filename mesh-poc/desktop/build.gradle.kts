import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
}

/** Single-file hub (window app; falls back to text mode without a display): java -jar offgrid-hub.jar */
tasks.register<Jar>("hubJar") {
    description = "Builds offgrid-hub.jar, the standalone household hub for Mac, Windows and Linux."
    archiveFileName.set("offgrid-hub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("hub"))
    manifest { attributes["Main-Class"] = "com.offgrid.hubapp.HubAppKt" }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
