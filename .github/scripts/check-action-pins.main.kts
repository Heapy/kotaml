#!/usr/bin/env kotlinr

/**
 * Checks workflow action and reusable workflow references for full commit SHA pins.
 * Usage: kotlinr .github/scripts/check-action-pins.main.kts [workflow.yml ...]
 * With no arguments, checks every .yml and .yaml file in .github/workflows.
 */
@file:DependsOn("io.heapy.kotaml:kotaml-jvm:0.111.0")
@file:CompilerOptions("-jvm-target", "11")

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

val pinnedReference = Regex("""[\w.-]+/[\w.-]+(?:/[\w.-]+)*@[0-9a-fA-F]{40}""")
val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
val errors = mutableListOf<String>()

fun YamlNode?.requireMap(location: String): YamlMap =
    requireNotNull(this as? YamlMap) { "$location: expected a mapping" }

fun checkReference(node: YamlMap, location: String) {
    val value = node.get<YamlNode>("uses") ?: return
    val reference = (value as? YamlScalar)?.content
    if (reference != null && (reference.startsWith("./") || pinnedReference.matches(reference))) return

    errors += "$location: non-local uses must end in a full 40-character commit SHA: ${value.contentToString()}"
}

val files =
    if (args.isEmpty()) {
        File(".github/workflows").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension in setOf("yml", "yaml") }
            .sortedBy { it.name }
    } else {
        args.map(::File)
    }

if (files.isEmpty()) {
    System.err.println("No workflow files found.")
    exitProcess(1)
}

for (file in files) {
    try {
        val workflow = yaml.parseToYamlNode(file.readText()).requireMap(file.path)
        val jobs = workflow.get<YamlNode>("jobs").requireMap("${file.path}:jobs")
        for ((jobId, node) in jobs.entries) {
            val location = "${file.path}:jobs.${jobId.content}"
            val job = node.requireMap(location)
            checkReference(job, location)
            val steps = job.get<YamlList>("steps")?.items.orEmpty()
            for ((index, step) in steps.withIndex()) {
                val stepLocation = "$location.steps[$index]"
                checkReference(step.requireMap(stepLocation), stepLocation)
            }
        }
    } catch (error: YamlException) {
        errors += "${file.path}: cannot validate workflow: $error"
    } catch (error: IOException) {
        errors += "${file.path}: cannot read workflow: ${error.message}"
    } catch (error: IllegalArgumentException) {
        errors += "${file.path}: cannot validate workflow: ${error.message}"
    }
}

if (errors.isNotEmpty()) {
    errors.forEach(System.err::println)
    exitProcess(1)
}
println("All action references are immutable in ${files.size} workflow(s).")
