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

import com.charleskorn.kaml.build.configureAssemble
import com.charleskorn.kaml.build.configurePublishing
import com.charleskorn.kaml.build.configureTesting
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotest)
    alias(libs.plugins.ksp)
}

group = "io.heapy.kotaml"

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    // Keep this target list in sync with the root build.gradle.kts.
    jvm {}

    js(IR) {
        browser()
        nodejs()
        binaries.executable()
    }

    wasmJs {
        binaries.library()
        browser()
        nodejs()
    }

    // Tier 1
    macosX64()
    macosArm64()
    iosSimulatorArm64()
    iosX64()

    // Tier 2
    linuxX64()
    linuxArm64()
    iosArm64()
    watchosSimulatorArm64()
    watchosX64()
    watchosArm32()
    watchosArm64()
    tvosSimulatorArm64()
    tvosX64()
    tvosArm64()

    // Tier 3
    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":"))
                api(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.framework.engine)
                // Overriding coroutines' version to solve a problem with WASM JS tests.
                // See https://kotlinlang.slack.com/archives/CDFP59223/p1736191408326039?thread_ts=1734964013.996149&cid=CDFP59223
                runtimeOnly(libs.kotlinx.coroutines.core)
                // kotest's kotlinx-io uses `import.meta` in its Wasm import object, which webpack
                // 5.108+ parses as an ES module, breaking the Karma bundle. Drop this once kotest
                // depends on kotlinx-io 0.9.1 or later. See https://youtrack.jetbrains.com/issue/KT-88162
                runtimeOnly(libs.kotlinx.io.core)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotest.runner.junit5)
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

configureAssemble()
configurePublishing(
    pomName = "kotaml-json",
    pomDescription = "Convert kotaml YAML node trees to kotlinx.serialization JSON node trees",
)
configureTesting()
