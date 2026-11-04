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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runHubDemo") {
    description = "Runs the household hub on the JVM (for browser testing)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.offgrid.mesh.hub.HubDemoKt")
    args = (project.findProperty("hubArgs") as String?)?.split(" ") ?: emptyList()
}
