#!/usr/bin/env kotlinr

/**
 * Targeted source mutation testing. Run from the repository root:
 * kotlinr scripts/mutation-test.main.kts [mutation-id ...]
 * No arguments runs the whole catalogue; --list prints it without building.
 * Only an isolated temporary source copy is changed. Reports remain in build/reports/mutation.
 */
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*

val core = "src/commonMain/kotlin/com/charleskorn/kaml/"
val json = "kotaml-json/src/commonMain/kotlin/com/charleskorn/kaml/json/"

data class Mutation(val id: String, val file: String, val before: String, val after: String)

val mutations = listOf(
    Mutation("depth-boundary", core + "YamlNodeReader.kt", "additionalDepth > maxNestingDepth - nestingDepth", "additionalDepth >= maxNestingDepth - nestingDepth"),
    Mutation("depth-accounting", core + "YamlNodeReader.kt", "maxNestingDepth - nestingDepth", "maxNestingDepth + nestingDepth"),
    Mutation("alias-depth", core + "YamlNodeReader.kt", "checkDepth(resolvedDepth, path, event)", "checkDepth(0, path, event)"),
    Mutation("alias-budget", core + "YamlNodeReader.kt", "aliasCount > maxAliasCount", "aliasCount >= maxAliasCount"),
    Mutation("merge-precedence", core + "YamlNodeReader.kt", "if (existingEntry == null)", "if (existingEntry != null)"),
    Mutation("sequence-tag-kind", core + "YamlNodeReader.kt", "tag == SEQUENCE_TAG && event !is SequenceStartEvent", "tag == SEQUENCE_TAG && event is SequenceStartEvent"),
    Mutation("octal-radix", core + "YamlNode.kt", "converter(content.substring(2), 8)", "converter(content.substring(2), 16)"),
    Mutation("merge-key-quoting", core + "YamlOutput.kt", "scalarStyle == ScalarStyle.PLAIN && (value.isNullLiteral() || value == \"<<\")", "scalarStyle == ScalarStyle.PLAIN && value.isNullLiteral()"),
    Mutation("unicode-break-escaping", core + "YamlOutput.kt", "value, safeStyle)", "value, style)"),
    Mutation("plain-colon-line-break", core + "YamlOutput.kt", "style == ScalarStyle.PLAIN && value.contains(\":\\n\")", "false"),
    Mutation("folded-indentation", core + "YamlOutput.kt", "style == ScalarStyle.FOLDED && (value.startsWith(' ') || value.contains(\"\\n \"))", "false"),
    Mutation("json-string-kind", json + "JsonToYaml.kt", "plain = !isString", "plain = isString"),
    Mutation("json-null-kind", json + "JsonToYaml.kt", "YamlNull(path)", "YamlScalar(\"null\", path, plain = false)"),
    Mutation("json-array-path", json + "JsonToYaml.kt", "withListEntry(index, SYNTHETIC_LOCATION)", "withListEntry(index + 1, SYNTHETIC_LOCATION)"),
    Mutation("quoted-json-scalar", json + "YamlToJson.kt", "if (!plain) return JsonPrimitive(content)", "if (plain) return JsonPrimitive(content)"),
    Mutation("non-finite-null-policy", json + "YamlToJson.kt", "NonFiniteNumbers.AS_NULL -> JsonNull", "NonFiniteNumbers.AS_NULL -> JsonPrimitive(content)"),
)

if (args.contentEquals(arrayOf("--list"))) {
    mutations.forEach { println("${it.id}: ${it.file}") }
} else {
    require(args.all { id -> mutations.any { it.id == id } }) { "Unknown mutation. Use --list to see the catalogue." }
    val selected = mutations.filter { args.isEmpty() || it.id in args }
    val root = Path.of("").toAbsolutePath().normalize()
    require(root.resolve("gradlew").isRegularFile() && root.resolve(core).isDirectory()) { "Run from the kotaml repository root." }
    // A unique report directory permits simultaneous runs without overwriting their evidence.
    val report = root.resolve("build/reports/mutation").createDirectories().resolve(System.currentTimeMillis().toString()).createDirectories()
    val workspace = Files.createTempDirectory("kotaml-mutation-")
    val excluded = setOf(".git", ".gradle", ".kotlin", ".idea", ".cifuzz-corpus", "node_modules", "kotlin-js-store")
    val testClasses = listOf("com.charleskorn.kaml.YamlRoundTripPropertyTest", "com.charleskorn.kaml.YamlReaderPropertyTest", "com.charleskorn.kaml.YamlWriterRegressionTest")
    val jsonTestClass = "com.charleskorn.kaml.json.JsonConversionPropertyTest"
    val results = mutableListOf<String>()
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }

    fun removeTree(path: Path) {
        if (!path.exists()) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    data class Run(val exit: Int, val cases: Int, val failed: List<String>, val counts: Map<String, Int> = emptyMap())

    fun runTests(id: String, jsonOnly: Boolean? = null): Run {
        // Remove old XML so an interrupted compiler or failed test discovery cannot count as a kill.
        val xmlDirectories = listOf("build/test-results/jvmTest", "kotaml-json/build/test-results/jvmTest")
        xmlDirectories.forEach { removeTree(workspace.resolve(it)) }
        val command = mutableListOf("./gradlew", "--console=plain", "--no-build-cache", "-Pkotlin.compiler.execution.strategy=in-process", "-PpropertySeed=20261025")
        if (jsonOnly != true) {
            command += ":jvmTest"
            testClasses.forEach { command += listOf("--tests", it) }
        }
        if (jsonOnly != false) command += listOf(":kotaml-json:jvmTest", "--tests", jsonTestClass)
        val output = report.resolve("$id.log")
        val process = ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor()
            return Run(-1, 0, emptyList())
        }
        var cases = 0
        val counts = mutableMapOf<String, Int>()
        val failed = mutableListOf<String>()
        for (directory in xmlDirectories) {
            val path = workspace.resolve(directory)
            if (!path.isDirectory()) continue
            Files.list(path).use { paths ->
                paths.filter { it.extension == "xml" }.forEach { xml ->
                    val document = factory.newDocumentBuilder().parse(xml.toFile())
                    val tests = document.getElementsByTagName("testcase")
                    for (index in 0 until tests.length) {
                        val test = tests.item(index) as org.w3c.dom.Element
                        if (test.getAttribute("classname") !in testClasses + jsonTestClass) continue
                        if (test.getElementsByTagName("skipped").length > 0) continue
                        cases++
                        val className = test.getAttribute("classname")
                        counts[className] = counts.getOrDefault(className, 0) + 1
                        if (test.getElementsByTagName("failure").length > 0 || test.getElementsByTagName("error").length > 0) {
                            failed += test.getAttribute("classname") + ": " + test.getAttribute("name")
                        }
                    }
                    Files.copy(xml, report.resolve("$id-${xml.fileName}"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return Run(process.exitValue(), cases, failed, counts)
    }

    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && (dir.fileName.toString() in excluded || (dir.fileName.toString() == "build" && root.relativize(dir).nameCount <= 2))) return FileVisitResult.SKIP_SUBTREE
                workspace.resolve(root.relativize(dir)).createDirectories()
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) Files.copy(file, workspace.resolve(root.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        // Check every replacement before spending time on compilation; source drift is an error.
        for (mutation in selected) {
            val source = workspace.resolve(mutation.file).readText()
            check(source.split(mutation.before).size == 2) { "${mutation.id}: expected exactly one mutation site" }
        }
        println("Baseline in $workspace; reports: $report")
        val baseline = runTests("baseline")
        check(baseline.exit == 0 && baseline.failed.isEmpty() && baseline.counts.keys == (testClasses + jsonTestClass).toSet()) { "Baseline failed or tests were not discovered; see ${report.resolve("baseline.log")}" }
        for (mutation in selected) {
            val file = workspace.resolve(mutation.file)
            val original = file.readText()
            println("Testing ${mutation.id}")
            val run = try {
                file.writeText(original.replace(mutation.before, mutation.after))
                runTests(mutation.id, mutation.file.startsWith("kotaml-json/"))
            } finally {
                file.writeText(original)
            }
            val expectedCases = if (mutation.file.startsWith("kotaml-json/")) baseline.counts.getValue(jsonTestClass) else testClasses.sumOf { baseline.counts.getValue(it) }
            val status = when {
                run.exit == -1 -> "TIMEOUT"
                run.cases != expectedCases -> "ERROR"
                run.exit == 0 && run.failed.isEmpty() -> "SURVIVED"
                run.exit != 0 && run.failed.isNotEmpty() -> "KILLED"
                else -> "ERROR"
            }
            results += "${mutation.id}\t$status\t${run.failed.joinToString("; ")}"
            report.resolve("results.tsv").writeText("mutation\tstatus\tfailing tests\n" + results.joinToString("\n") + "\n")
            println("${mutation.id}: $status (${run.failed.size} failed tests)")
        }
        // Restore and test the original sources once more to rule out a contaminated build.
        val restored = runTests("restored")
        check(restored.exit == 0 && restored.cases == baseline.cases && restored.failed.isEmpty()) { "Restored baseline failed; see $report" }
        val killed = results.count { "\tKILLED\t" in it }
        println("$killed/${selected.size} targeted mutations killed. Report: $report")
        check(killed == selected.size) { "Surviving mutations or infrastructure errors; see results.tsv" }
    } finally {
        removeTree(workspace)
    }
}
