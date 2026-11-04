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
    implementation("com.google.zxing:core:3.5.3") // QR codes (hub invite page)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runHubDemo") {
    description = "Runs the household hub on this computer (for testing): gradle -p core runHubDemo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offgrid.mesh.hub.HubMainKt")
    args = (project.findProperty("hubArgs") as String?)?.split(" ") ?: emptyList()
}

/** Single-file hub for Chromebooks (Linux) and any computer with Java 17+: java -jar offgrid-hub.jar */
tasks.register<Jar>("hubJar") {
    description = "Builds offgrid-hub.jar, the standalone household hub."
    archiveFileName.set("offgrid-hub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("hub"))
    manifest { attributes["Main-Class"] = "com.offgrid.mesh.hub.HubMainKt" }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
