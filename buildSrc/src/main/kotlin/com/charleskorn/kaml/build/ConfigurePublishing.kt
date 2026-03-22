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

package com.charleskorn.kaml.build

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

fun Project.configurePublishing() {
    apply<MavenPublishPlugin>()
    apply<SigningPlugin>()

    createPublishingTasks()
    createSigningTasks()
}

private fun Project.createPublishingTasks() {
    configure<PublishingExtension> {
        publications.withType<MavenPublication> {
            // HACK: this is a workaround while we're waiting to get Dokka set up correctly
            // (see https://kotlinlang.slack.com/archives/C0F4UNJET/p1616470404031100?thread_ts=1616198351.029900&cid=C0F4UNJET)
            // This creates an empty JavaDoc JAR to make Maven Central happy.
            val publicationName = this.name
            val javadocTask =
                tasks.register<Jar>(this.name + "JavadocJar") {
                    archiveClassifier.set("javadoc")
                    archiveBaseName.set("kaml-$publicationName")
                }

            artifact(javadocTask)

            pom {
                name.set("kotaml")
                description.set("YAML support for kotlinx.serialization")
                url.set("https://github.com/Heapy/kotaml")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("charleskorn")
                        name.set("Charles Korn")
                        email.set("me@charleskorn.com")
                    }
                    developer {
                        id.set("ruslan.ibrahimau")
                        name.set("Ruslan Ibrahimau")
                        email.set("ruslan@heapy.io")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/Heapy/kotaml.git")
                    developerConnection.set("scm:git:ssh://github.com:Heapy/kotaml.git")
                    url.set("https://github.com/Heapy/kotaml")
                }
            }
        }

        repositories {
            maven {
                url =
                    rootProject.layout.buildDirectory
                        .dir("staging-deploy")
                        .get()
                        .asFile
                        .toURI()
            }
        }
    }
}

private fun Project.createSigningTasks() {
    configure<SigningExtension> {
        sign(publishing.publications)
    }
}

private val Project.publishing: PublishingExtension
    get() = extensions.getByType<PublishingExtension>()
