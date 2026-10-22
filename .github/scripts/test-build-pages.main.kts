#!/usr/bin/env kotlinr

/** Regression checks for Kover totals, empty metrics, invalid input and Pages packaging. */
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

val temporary = Files.createTempDirectory("kotaml-pages-test-").toFile()
val tasks = temporary.resolve("tasks")
val generator = File(".github/scripts/build-pages.main.kts").absolutePath
val sha = "0123456789abcdef0123456789abcdef01234567"
val date = "2026-10-13T12:00:00Z"

fun xml(line: String = """<counter type="LINE" covered="3" missed="1"/>""", branch: String = """<counter type="BRANCH" covered="0" missed="0"/>""") = """
    <report name="fixture"><package name="nested"><counter type="LINE" covered="999" missed="0"/></package>$line$branch</report>
""".trimIndent()

fun run(name: String, success: Boolean = true): File {
    val output = temporary.resolve(name)
    val process = ProcessBuilder("kotlinr", generator, tasks.path, output.path, sha, date)
        .redirectErrorStream(true).start()
    val log = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check((exit == 0) == success) { "$name: unexpected exit $exit\n$log" }
    if (!success) check(!output.exists()) { "Invalid input created a partial site" }
    return output
}

try {
    for (module in listOf("kotaml", "kotaml-json")) {
        val coverage = tasks.resolve("_${module}_koverReport@kover").apply { mkdirs() }
        coverage.resolve("coverage.xml").writeText(xml())
        for (type in listOf("koverReport@kover", "dokkaHtml@dokka")) {
            val html = tasks.resolve("_${module}_$type/html").apply { mkdirs() }
            html.resolve("index.html").writeText("<html><body>$module $type</body></html>")
        }
    }
    val output = run("valid")
    val svg = output.resolve("badges/kotaml-lines.svg")
    check("75.00%" in svg.readText() && sha in svg.readText() && date in svg.readText())
    check("n/a" in output.resolve("badges/kotaml-branches.svg").readText())
    output.resolve("badges").listFiles()!!.forEach {
        check(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement.tagName == "svg")
    }
    check("3 / 4" in output.resolve("coverage/kotaml/index.html").readText())
    check(output.resolve("coverage/kotaml/report/index.html").isFile)
    check(output.resolve("api/kotaml-json/index.html").isFile)
    check(output.resolve("coverage/kotaml/coverage.xml").isFile)
    val report = tasks.resolve("_kotaml_koverReport@kover/coverage.xml")
    report.writeText(xml(line = ""))
    run("missing-total", success = false)
    report.writeText(xml(line = """<counter type="LINE" covered="-1" missed="1"/>"""))
    run("negative-total", success = false)
    report.writeText(xml(branch = """<counter type="BRANCH" covered="1" missed="2"/>"""))
    val thirds = run("rounding")
    check("33.33%" in thirds.resolve("badges/kotaml-branches.svg").readText())
    tasks.resolve("_kotaml-json_dokkaHtml@dokka/html/index.html").delete()
    run("missing-docs", success = false)
    println("Pages generator tests passed")
} finally {
    temporary.deleteRecursively()
}
