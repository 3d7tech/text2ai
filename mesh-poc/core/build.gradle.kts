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
