#!/usr/bin/env kotlinr

/**
 * KTC 0.13 NativeTestTask executes tvOS/watchOS binaries directly on the host.
 * Link with KTC, then run in the matching runtime through simctl (as Gradle does).
 * Usage: kotlinr .github/scripts/test-apple-simulators.main.kts
 */
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

val root = File(".").canonicalFile
val logs = root.resolve("build/simulator-tests").apply { mkdirs() }

fun run(command: List<String>, name: String, timeoutMinutes: Long = 30): String {
    val log = logs.resolve("$name.log")
    val process = ProcessBuilder(command).directory(root).redirectErrorStream(true).redirectOutput(log).start()
    try {
        check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) { "Timed out: $command; see $log" }
        val output = log.readText()
        check(process.exitValue() == 0) { "Failed (${process.exitValue()}): $command\n${output.takeLast(12000)}" }
        return output.trim()
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly().waitFor()
        }
    }
}

check(System.getProperty("os.name").startsWith("Mac")) { "Apple simulators require macOS" }
val runtimes = Json.parseToJsonElement(run(listOf("xcrun", "simctl", "list", "runtimes", "-j"), "runtimes"))
    .jsonObject.getValue("runtimes").jsonArray.map { it.jsonObject }
val targets = listOf("tvOS" to "TvosSimulatorArm64", "watchOS" to "WatchosSimulatorArm64")
// Validate both runtimes before expensive compilation. Never silently skip simulator tests.
val selected = targets.associate { (platform, target) ->
    val runtime = runtimes.filter {
        it["isAvailable"]?.jsonPrimitive?.booleanOrNull == true &&
            it.getValue("identifier").jsonPrimitive.content.contains(".$platform-")
    }.maxByOrNull {
        it.getValue("version").jsonPrimitive.content.split('.').joinToString(".") { part -> part.padStart(4, '0') }
    } ?: error("No available $platform simulator runtime. Install with: xcodebuild -downloadPlatform $platform")
    target to runtime
}

val tasks = selected.keys.flatMap { target ->
    listOf("kotaml", "kotaml-json").map { module -> ":$module:link${target}TestDebug" }
}
println("Linking tvOS/watchOS tests with Kotlin Toolchain")
run(listOf(root.resolve("kotlin").path, "task") + tasks, "link")
for ((target, runtime) in selected) {
    val runtimeId = runtime.getValue("identifier").jsonPrimitive.content
    val deviceType = runtime.getValue("supportedDeviceTypes").jsonArray.first().jsonObject
        .getValue("identifier").jsonPrimitive.content
    val device = run(listOf("xcrun", "simctl", "create", "kotaml-tests-${UUID.randomUUID()}", deviceType, runtimeId), "$target-create")
    require(Regex("[A-Fa-f0-9-]{36}").matches(device)) { "Invalid simulator identifier: $device" }
    try {
        for (module in listOf("kotaml", "kotaml-json")) {
            val directory = root.resolve("build/tasks/_${module}_link${target}TestDebug")
            // dSYM bundles also contain a file named *.kexe; only the top-level file is executable.
            val binary = directory.listFiles().orEmpty().single { it.isFile && it.extension == "kexe" }
            println("Testing $module on $runtimeId")
            // Standalone console tests need no booted UI or paired watch; simctl supplies DYLD_ROOT_PATH.
            val result = run(listOf("xcrun", "simctl", "spawn", "--standalone", device, binary.path), "$module-$target", 10)
            println(result.lineSequence().filter { it.contains("Tests:") || it.contains("All tests passed") }.joinToString("\n"))
        }
    } finally {
        // Delete only the disposable device created by this script.
        run(listOf("xcrun", "simctl", "delete", device), "$target-delete", 2)
    }
}
println("tvOS and watchOS simulator tests passed for both modules")
