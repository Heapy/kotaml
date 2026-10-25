#!/usr/bin/env kotlinr

/** Render local PlantUML sources. Requires PlantUML and Graphviz on PATH. */
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.12.0")
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*
import kotlinx.serialization.json.*

require(args.size <= 1) { "Usage: kotlinr docs/render.main.kts [docs-directory]" }
val docs = Path.of(args.firstOrNull() ?: "docs").toAbsolutePath().normalize()
val sources = docs.resolve("diagrams").listDirectoryEntries("*.puml").sortedBy { it.name }
check(sources.isNotEmpty()) { "No PlantUML sources found in $docs/diagrams" }
val output = docs.resolve("site/diagrams").createDirectories()
val command = listOf("plantuml", "-tsvg", "-nometadata", "-charset", "UTF-8", "-o", output.toString()) + sources.map(Path::toString)
check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) { "PlantUML rendering failed" }
val versionProcess = ProcessBuilder("plantuml", "-version").redirectError(ProcessBuilder.Redirect.INHERIT).start()
val version = versionProcess.inputStream.bufferedReader().use { it.readText() }
check(versionProcess.waitFor() == 0) { "Cannot read PlantUML version" }

val paths = sources + listOf(docs.resolve("diagrams/theme.puml.inc")) + output.listDirectoryEntries("*.svg").sortedBy { it.name }
val manifest = buildJsonObject {
    put("generator", version.lineSequence().first())
    putJsonObject("files") {
        for (path in paths) {
            put(path.relativeTo(docs).invariantSeparatorsPathString, MessageDigest.getInstance("SHA-256").digest(path.readBytes()).toHexString())
        }
    }
}
val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
docs.resolve("diagrams/manifest.json").writeText(json.encodeToString(JsonObject.serializer(), manifest) + "\n")
println("Rendered ${sources.size} diagrams into $output")
