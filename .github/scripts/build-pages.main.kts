#!/usr/bin/env kotlinr

/** Build the Pages site from completed Kover and Dokka tasks; no network or dependencies.
 * Usage: kotlinr .github/scripts/build-pages.main.kts <tasks-dir> <output-dir> <commit-sha> [ISO-instant]
 */

import java.io.File
import java.time.Instant
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class Coverage(val covered: Long, val missed: Long) {
    val total = Math.addExact(covered, missed)
    val percent: Double? = if (total == 0L) null else covered.toDouble() * 100 / total
    val label: String = percent?.let { String.format(Locale.ROOT, "%.2f%%", it) } ?: "n/a"
}

fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

fun counters(file: File): Map<String, Coverage> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val report = factory.newDocumentBuilder().parse(file).documentElement
    require(report.tagName == "report") { "Expected a Kover report: $file" }
    // Only report totals: package/class/method counters would count the same code repeatedly.
    val children = (0 until report.childNodes.length).mapNotNull { report.childNodes.item(it) as? Element }
    return listOf("LINE", "BRANCH").associateWith { type ->
        val node = children.singleOrNull { it.tagName == "counter" && it.getAttribute("type") == type }
        requireNotNull(node) { "Missing or duplicate $type total in $file" }
        val covered = node.getAttribute("covered").toLong()
        val missed = node.getAttribute("missed").toLong()
        require(covered >= 0 && missed >= 0) { "Negative $type counter in $file" }
        Coverage(covered, missed)
    }
}

fun badge(label: String, coverage: Coverage, provenance: String): String {
    val left = label.length * 7 + 16
    val right = 68
    val width = left + right
    val color = when {
        coverage.percent == null -> "#6b7280"
        coverage.percent >= 90 -> "#16803c"
        coverage.percent >= 75 -> "#996000"
        else -> "#b91c1c"
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="24" role="img" aria-label="${escape("$label: ${coverage.label}")}">
      <title>${escape("$label: ${coverage.label}; $provenance")}</title>
      <rect width="$width" height="24" rx="4" fill="#374151"/>
      <path d="M$left 0H${width - 4}Q$width 0 $width 4V20Q$width 24 ${width - 4} 24H$left Z" fill="$color"/>
      <g fill="white" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11" text-anchor="middle">
        <text x="${left / 2}" y="16">${escape(label)}</text>
        <text x="${left + right / 2}" y="16">${coverage.label}</text>
      </g>
    </svg>
    """.trimIndent()
}

fun page(title: String, provenance: String, body: String): String = """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${escape(title)}</title><style>
    body{font:16px/1.6 system-ui,sans-serif;max-width:850px;margin:3rem auto;padding:0 1rem;color:#17212b}
    a{color:#0759a5}table{border-collapse:collapse}td,th{padding:.5rem 1rem;text-align:left;border-bottom:1px solid #ddd}
    </style></head><body><h1>${escape(title)}</h1>$provenance$body</body></html>
""".trimIndent() + "\n"

require(args.size in 3..4) { "Usage: build-pages.main.kts <tasks-dir> <output-dir> <commit-sha> [ISO-instant]" }
val tasks = File(args[0])
val output = File(args[1])
val sha = args[2]
require(Regex("[0-9a-f]{40}").matches(sha)) { "Expected full commit SHA" }
val generated = args.getOrNull(3)?.let(Instant::parse) ?: Instant.now()
val modules = listOf("kotaml", "kotaml-json")
val reports = modules.associateWith { module -> counters(tasks.resolve("_${module}_koverReport@kover/coverage.xml")) }
for (module in modules) {
    for (path in listOf("_${module}_koverReport@kover/html", "_${module}_dokkaHtml@dokka/html")) {
        require(tasks.resolve("$path/index.html").isFile) { "Missing report: $path/index.html" }
        require(tasks.resolve(path).walkTopDown().none { java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            "Pages artifacts must not contain symbolic links: $path"
        }
    }
}
require(!output.exists() || output.isDirectory && output.listFiles()!!.isEmpty()) { "Output must be empty: $output" }
check(output.isDirectory || output.mkdirs())
val provenance = "<p>Commit <a href=\"https://github.com/Heapy/kotaml/commit/$sha\"><code>${sha.take(12)}</code></a> · Generated <time>$generated</time> (UTC)</p>"
val badges = output.resolve("badges").apply { mkdirs() }
for ((module, totals) in reports) {
    val destination = output.resolve("coverage/$module").apply { mkdirs() }
    check(tasks.resolve("_${module}_koverReport@kover/html").copyRecursively(destination.resolve("report")))
    tasks.resolve("_${module}_koverReport@kover/coverage.xml").copyTo(destination.resolve("coverage.xml"))
    val rows = listOf("LINE" to "lines", "BRANCH" to "branches").joinToString("\n") { (type, name) ->
        val coverage = totals.getValue(type)
        badges.resolve("$module-$name.svg").writeText(badge("$module JVM $name", coverage, "$sha; $generated"))
        println("$module $name: ${coverage.label} (${coverage.covered}/${coverage.total})")
        "<tr><th>$name</th><td>${coverage.label}</td><td>${coverage.covered} / ${coverage.total}</td></tr>"
    }
    destination.resolve("index.html").writeText(page("$module JVM coverage", provenance, """
        <p>Coverage from JVM tests. Branch coverage does not measure every combination of conditions.</p>
        <table><thead><tr><th>Metric</th><th>Coverage</th><th>Covered / total</th></tr></thead><tbody>$rows</tbody></table>
        <p><a href="report/index.html">Browse the Kover report</a> · <a href="coverage.xml">XML report</a> · <a href="../../index.html">All reports</a></p>
    """.trimIndent()))
    check(tasks.resolve("_${module}_dokkaHtml@dokka/html").copyRecursively(output.resolve("api/$module")))
}
val links = modules.joinToString("\n") { module ->
    "<li><strong>$module</strong>: <a href=\"coverage/$module/\">JVM coverage</a> · <a href=\"api/$module/\">KMP API documentation</a></li>"
}
output.resolve("index.html").writeText(page("kotaml documentation and coverage", provenance, "<ul>$links</ul>"))
output.resolve(".nojekyll").writeText("")
println("Pages site: ${output.absolutePath}")
