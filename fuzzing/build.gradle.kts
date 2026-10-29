/*

   Copyright 2018-2023 Charles Korn.
   Copyright 2026 Ruslan Ibrahimau.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(project(":"))
    testImplementation(libs.okio)
    testImplementation(libs.jazzer.junit)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Temporary quarantine for the two URI double-decoding failures in Engine 5.0.0.
// Keep the source seeds; changing the engine version automatically re-enables them.
val quarantineKnownReaderFailures =
    libs.versions.snakeyaml.engine.kmp
        .get() == "5.0.0" &&
        !providers.gradleProperty("fuzzIncludeKnownFailures").map(String::toBooleanStrict).getOrElse(false)
val knownReaderFailures =
    mapOf(
        "crash-7895f33f6e9e327248d69d3bf4a934dfe0dcc0e3" to "71896d4ac12e007f",
        "crash-487d9a846211b97053cc6df99deb2df8e394cad4" to "c3518e77b28b31d7",
    )

tasks.processTestResources {
    if (quarantineKnownReaderFailures) {
        knownReaderFailures.keys.forEach { seed ->
            exclude("com/charleskorn/kaml/fuzz/YamlReaderFuzzTestInputs/read/$seed")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    maxParallelForks = 1
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-XX:-OmitStackTraceInFastThrow")
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    systemProperty("jazzer.instrumentation_includes", listOf("com.charleskorn.kaml.**", "it.krzeminski.snakeyaml.engine.kmp.**").joinToString(File.pathSeparator))
    systemProperty("jazzer.instrumentation_excludes", "com.charleskorn.kaml.fuzz.**")
    testLogging {
        events("failed", "standard_out", "standard_error")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Normal check/test runs replay only the checked-in seeds, even if the shell enables fuzzing.
// Jazzer installs one agent per JVM, so give each target its own process in regression mode too.
tasks.test {
    environment("JAZZER_FUZZ", "")
    environment("JAZZER_COVERAGE", "")
    forkEvery = 1
    if (quarantineKnownReaderFailures) {
        doFirst { logger.lifecycle("Quarantining two known SnakeYAML URI seeds; use -PfuzzIncludeKnownFailures=true to replay them.") }
    }
}

for (target in listOf("Reader", "Writer")) {
    tasks.register<Test>("fuzz$target") {
        group = "verification"
        description = "Coverage-guided $target fuzzing (default: 5 minutes; override with -PfuzzDuration=30s)."
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter.includeTestsMatching("com.charleskorn.kaml.fuzz.Yaml${target}FuzzTest")
        environment("JAZZER_FUZZ", "1")
        // Jazzer translates @Timeout into libFuzzer's per-input timeout. JUnit would
        // otherwise time the entire campaign as one invocation of the fuzz method.
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
        systemProperty("jazzer.max_duration", providers.gradleProperty("fuzzDuration").getOrElse("5m"))
        if (target == "Reader" && quarantineKnownReaderFailures) {
            // Ignore only these known stack fingerprints, not whole exception classes or tags.
            systemProperty("jazzer.ignore", knownReaderFailures.values.joinToString(","))
            doFirst { logger.lifecycle("Ignoring two known SnakeYAML URI fingerprints; use -PfuzzIncludeKnownFailures=true to include them.") }
        }
        val report =
            layout.buildDirectory
                .dir("fuzz/$target")
                .get()
                .asFile
        systemProperty("jazzer.coverage_report", report.resolve("coverage.txt").absolutePath)
        systemProperty("jazzer.reproducer_path", report.absolutePath)
        doFirst { report.mkdirs() }
        // A search must run again even when the sources did not change.
        outputs.upToDateWhen { false }
    }
}

tasks.named("fuzzWriter") { mustRunAfter("fuzzReader") }
