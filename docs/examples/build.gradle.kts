plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.heapy.kotaml:kotaml:0.112.0")
    implementation("io.heapy.kotaml:kotaml-json:0.112.0")
}

application {
    mainClass.set("example.readwrite.MainKt")
}

tasks.register<JavaExec>("runTree") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("example.tree.MainKt")
}

tasks.register<JavaExec>("runJson") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("example.json.MainKt")
}
