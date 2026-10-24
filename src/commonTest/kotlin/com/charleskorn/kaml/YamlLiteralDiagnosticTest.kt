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

package com.charleskorn.kaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import it.krzeminski.snakeyaml.engine.kmp.exceptions.ScannerException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

class YamlLiteralDiagnosticTest :
    FlatFunSpec({
        val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
        for (glob in listOf("*.html", "*.png")) {
            for (layout in listOf("root", "list", "property")) {
                test("explains scanner alias $glob in $layout without losing its cause or path") {
                    val error =
                        shouldThrow<MalformedYamlException> {
                            when (layout) {
                                "root" -> yaml.decodeFromString<String>(glob)
                                "list" -> yaml.decodeFromString<List<String>>("- ok\n- $glob")
                                else -> yaml.decodeFromString<DiagnosticStringProperty>("value: $glob")
                            }
                        }
                    val location =
                        when (layout) {
                            "root" -> Location(1, 1)
                            "list" -> Location(2, 3)
                            else -> Location(1, 8)
                        }
                    val path =
                        when (layout) {
                            "property" -> YamlPath.root.withMapElementKey("value", Location(1, 1))
                            else -> YamlPath.root
                        }
                    error.location shouldBe location
                    error.path shouldBe path.withError(location)
                    val cause = error.cause.shouldBeInstanceOf<ScannerException>()
                    cause.problem shouldBe "while scanning an alias"
                    error.message shouldContain "In YAML, '*' starts an alias. If you meant literal text, put it in quotes."
                    error.message shouldContain "quotes"
                }
            }
        }
        for (layout in listOf("root", "list", "property")) {
            test("explains undefined alias in $layout") {
                val error =
                    shouldThrow<UnknownAnchorException> {
                        when (layout) {
                            "root" -> yaml.decodeFromString<String>("*missing")
                            "list" -> yaml.decodeFromString<List<String>>("- ok\n- *missing")
                            else -> yaml.decodeFromString<DiagnosticStringProperty>("value: *missing")
                        }
                    }
                error.anchorName shouldBe "missing"
                val path =
                    when (layout) {
                        "root" -> {
                            YamlPath.root.withError(Location(1, 1))
                        }

                        "list" -> {
                            YamlPath.root.withListEntry(1, Location(2, 3)).withError(Location(2, 3))
                        }

                        else -> {
                            YamlPath.root
                                .withMapElementKey("value", Location(1, 1))
                                .withMapElementValue(Location(1, 8))
                                .withError(Location(1, 8))
                        }
                    }
                error.path shouldBe path
                error.cause shouldBe null
                error.message shouldContain "In YAML, '*' starts an alias. If you meant literal text, put it in quotes."
            }
            for (tag in listOf("!.git", "!custom")) {
                test("explains tagged null String $tag in $layout and keeps the original null failure") {
                    val outer =
                        shouldThrow<YamlException> {
                            when (layout) {
                                "root" -> yaml.decodeFromString<String>(tag)
                                "list" -> yaml.decodeFromString<List<String>>("- ok\n- $tag")
                                else -> yaml.decodeFromString<DiagnosticStringProperty>("value: $tag")
                            }
                        }
                    val error =
                        if (layout == "property") {
                            outer.shouldBeInstanceOf<InvalidPropertyValueException>().cause.shouldBeInstanceOf<UnexpectedNullValueException>()
                        } else {
                            outer.shouldBeInstanceOf<UnexpectedNullValueException>()
                        }
                    val path =
                        when (layout) {
                            "root" -> YamlPath.root
                            "list" -> YamlPath.root.withListEntry(1, Location(2, 3))
                            else -> YamlPath.root.withMapElementKey("value", Location(1, 1)).withMapElementValue(Location(1, 8))
                        }
                    outer.path shouldBe path
                    error.path shouldBe path
                    error.cause.shouldBeInstanceOf<UnexpectedNullValueException>().path shouldBe path
                    error.message shouldBe
                        "Unexpected null or empty value for non-null field. YAML tag '$tag' applies to this null value. " +
                        "If you meant literal text, put it in quotes."
                    outer.message shouldContain "tag '$tag'"
                }
            }
        }
        test("explains aliases disabled at a real alias without suggesting aliases for anchors") {
            val alias = shouldThrow<ForbiddenAnchorOrAliasException> { Yaml.default.decodeFromString<String>("*missing") }
            alias.message shouldContain "In YAML, '*' starts an alias. If you meant literal text, put it in quotes."
            alias.path shouldBe YamlPath.root
            val anchor = shouldThrow<ForbiddenAnchorOrAliasException> { Yaml.default.decodeFromString<String>("&defined value") }
            anchor.message shouldNotContain "If you meant literal text"
        }
        for (input in listOf("&.html value", "[one, two", "!<unfinished", "value: [broken", "'*.html': [broken")) {
            test("does not suggest quoting literal text for unrelated syntax $input") {
                val error = shouldThrow<MalformedYamlException> { yaml.parseToYamlNode(input) }
                error.message shouldNotContain "'*' starts an alias"
                error.message shouldNotContain "If you meant literal text"
            }
        }
        for (input in listOf("null", "~")) {
            test("ordinary $input keeps its original null diagnosis") {
                val error = shouldThrow<UnexpectedNullValueException> { yaml.decodeFromString<String>(input) }
                error.message shouldBe "Unexpected null or empty value for non-null field."
                error.cause shouldBe null
            }
        }
        test("keeps the enclosing property path for a scanner alias in a nested list") {
            val error =
                shouldThrow<MalformedYamlException> {
                    yaml.decodeFromString<DiagnosticListProperty>("values:\n- ok\n- *.png")
                }
            error.path shouldBe
                YamlPath.root
                    .withMapElementKey("values", Location(1, 1))
                    .withMapElementValue(Location(2, 1))
                    .withError(Location(3, 3))
            error.cause.shouldBeInstanceOf<ScannerException>()
            error.message shouldContain "In YAML, '*' starts an alias. If you meant literal text, put it in quotes."
        }
        test("keeps the list index and property wrapper for tagged null in a nested list") {
            val error =
                shouldThrow<InvalidPropertyValueException> {
                    yaml.decodeFromString<DiagnosticListProperty>("values:\n- ok\n- !.git")
                }
            val path =
                YamlPath.root
                    .withMapElementKey("values", Location(1, 1))
                    .withMapElementValue(Location(2, 1))
                    .withListEntry(1, Location(3, 3))
            error.path shouldBe path
            error.cause
                .shouldBeInstanceOf<UnexpectedNullValueException>()
                .cause
                .shouldBeInstanceOf<UnexpectedNullValueException>()
                .path shouldBe path
            error.message shouldContain "tag '!.git'"
        }
        test("quotes preserve globs, tags, versions and port mappings in String lists") {
            val input = "- \"*.html\"\n- '*.png'\n- \"!.git\"\n- 1.20\n- 22:22"
            yaml.decodeFromString<List<String>>(input) shouldBe listOf("*.html", "*.png", "!.git", "1.20", "22:22")
            for (text in listOf("*.html", "*.png", "!.git")) {
                yaml.decodeFromString<String>("'$text'") shouldBe text
                yaml.decodeFromString<DiagnosticStringProperty>("value: '$text'").value shouldBe text
            }
        }
        test("tagged null stays null for nullable String at every position") {
            yaml.decodeFromString<String?>("!.git") shouldBe null
            yaml.decodeFromString<List<String?>>("- !.git") shouldBe listOf(null)
            yaml.decodeFromString<DiagnosticNullableProperty>("value: !.git").value shouldBe null
        }
        test("tagged scalar strings and valid aliases keep their meaning") {
            yaml.decodeFromString<String>("!custom text") shouldBe "text"
            yaml.decodeFromString<List<String>>("- &value text\n- *value") shouldBe listOf("text", "text")
            yaml.decodeFromString<String>("!!str null") shouldBe "null"
        }
        test("polymorphic tags retain dispatch and unknown-type diagnostics") {
            val tagged = Yaml(configuration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Tag))
            tagged.decodeFromString<DiagnosticBase>("!<diagnostic> {value: text}") shouldBe DiagnosticSubtype("text")
            shouldThrow<UnknownPolymorphicTypeException> { yaml.decodeFromString<DiagnosticBase>("!.git") }
        }
    })

@Serializable
private data class DiagnosticStringProperty(
    val value: String,
)

@Serializable
private data class DiagnosticNullableProperty(
    val value: String?,
)

@Serializable
private sealed interface DiagnosticBase

@Serializable
@SerialName("diagnostic")
private data class DiagnosticSubtype(
    val value: String,
) : DiagnosticBase

@Serializable
private data class DiagnosticListProperty(
    val values: List<String>,
)
