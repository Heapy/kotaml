#!/usr/bin/env kotlinr

/** Check local links, image descriptions and diagram freshness. Run from the repository root. */
@file:DependsOn("org.jsoup:jsoup:1.23.2")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.12.0")

import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

require(args.size <= 1) { "Usage: kotlinr docs/check.main.kts [docs-directory]" }
val docs = Path.of(args.firstOrNull() ?: "docs").toAbsolutePath().normalize()
val site = docs.resolve("site")
val page = Jsoup.parse(site.resolve("index.html").readText())
val ids = mutableSetOf<String>()
for (element in page.select("[id]")) {
    check(ids.add(element.id())) { "Duplicate ID: ${element.id()}" }
}
for (image in page.select("img")) {
    check(image.attr("alt").isNotBlank()) { "An image is missing its description" }
}
val links = page.allElements.flatMap { element ->
    listOf("src", "href").filter(element::hasAttr).map(element::attr)
}
for (link in links) {
    val uri = URI(link)
    if (uri.isAbsolute || uri.rawAuthority != null) continue
    val path = uri.path.orEmpty()
    if (path.isNotEmpty()) {
        val target = site.resolve(path).normalize()
        check(!path.startsWith("/") && target.startsWith(site)) { "Use a project-relative URL: $link" }
        check(target.isRegularFile()) { "Broken local link: $link" }
    } else if (!uri.fragment.isNullOrEmpty()) {
        check(uri.fragment in ids) { "Broken anchor: $link" }
    }
}

fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).toHexString()

val manifest = Json.parseToJsonElement(docs.resolve("diagrams/manifest.json").readText()).jsonObject
val files = manifest.getValue("files").jsonObject
for ((name, expected) in files) {
    check(sha256(docs.resolve(name)) == expected.jsonPrimitive.content) {
        "Diagram changed: $name; run kotlinr docs/render.main.kts"
    }
}
val xml = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
}.newDocumentBuilder()
val sources = docs.resolve("diagrams").listDirectoryEntries("*.puml")
check(sources.isNotEmpty()) { "No PlantUML sources found in $docs/diagrams" }
for (source in sources) {
    val svg = site.resolve("diagrams/${source.nameWithoutExtension}.svg")
    check(source.relativeTo(docs).invariantSeparatorsPathString in files) { "Unrendered source: $source" }
    check(svg.relativeTo(docs).invariantSeparatorsPathString in files) { "Unrecorded SVG: $svg" }
    val root = xml.parse(svg.toFile()).documentElement
    check(root.localName == "svg" && root.namespaceURI == "http://www.w3.org/2000/svg") { "Invalid SVG: $svg" }
    check("Syntax Error" !in svg.readText()) { "PlantUML error: $source" }
}
println("Checked ${links.size} links and ${sources.size} PlantUML diagrams.")
