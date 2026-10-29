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
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.Buffer

@OptIn(ExperimentalKotest::class)
class YamlReaderPropertyTest :
    FlatFunSpec({
        for (seed in listOf(510L, null)) {
            val config = PropTestConfig(iterations = 150, seed = seed ?: PropertyTesting.defaultSeed)
            test("mixed collection depth accepts its boundary and rejects one less, seed=$seed") {
                checkAll(config, Arb.list(Arb.boolean(), 1..20)) { wrappers ->
                    val document = wrappers.fold("value") { child, list -> if (list) "[$child]" else "{key: $child}" }
                    val depth = wrappers.size
                    Yaml(configuration = YamlConfiguration(maxNestingDepth = depth)).parseToYamlNode(document)
                    val failure =
                        shouldThrow<MalformedYamlException> {
                            Yaml(configuration = YamlConfiguration(maxNestingDepth = depth - 1)).parseToYamlNode(document)
                        }
                    failure.message shouldBe "Maximum YAML nesting depth of ${depth - 1} exceeded."
                }
            }
            test("aliases respect exact budget and relocate expanded list paths, seed=$seed") {
                checkAll(config, Arb.int(2..20), Arb.int()) { copies, value ->
                    val document = "base: &base [$value]\nrefs: [" + List(copies) { "*base" }.joinToString(", ") + "]"
                    val configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted(copies.toUInt()))
                    val root = Yaml(configuration = configuration).parseToYamlNode(document).yamlMap
                    val references = root.get<YamlList>("refs")!!
                    references.items.forEachIndexed { index, node ->
                        node.yamlList.items
                            .single()
                            .yamlScalar
                            .toInt() shouldBe value
                        node.yamlList.items
                            .single()
                            .path
                            .toHumanReadableString() shouldBe "refs[$index]->&base[0]"
                    }
                    shouldThrow<ForbiddenAnchorOrAliasException> {
                        Yaml(configuration = configuration.copy(anchorsAndAliases = AnchorsAndAliases.Permitted((copies - 1).toUInt()))).parseToYamlNode(document)
                    }
                    shouldThrow<MalformedYamlException> {
                        Yaml(configuration = configuration.copy(maxNestingDepth = 2)).parseToYamlNode(document)
                    }
                }
            }
            test("merge precedence keeps explicit entries and the first merged mapping, seed=$seed") {
                checkAll(config, Arb.int(), Arb.int()) { first, second ->
                    val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
                    val document =
                        """
                        first: &first {shared: $first, inherited: $first}
                        second: &second {shared: $second, other: $second}
                        merged: {<<: [*first, *second], other: $first}
                        """.trimIndent()
                    yaml.decodeFromString<Map<String, Map<String, Int>>>(document).getValue("merged") shouldBe mapOf("shared" to first, "inherited" to first, "other" to first)
                }
            }
            test("duplicate key text is rejected regardless of value, seed=$seed") {
                checkAll(config, Arb.int(), Arb.int()) { first, second ->
                    shouldThrow<DuplicateKeyException> { Yaml.default.parseToYamlNode("{key: $first, 'key': $second}") }
                }
            }
            test("collection tags require matching node kinds, seed=$seed") {
                checkAll(config, Arb.int()) { value ->
                    shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("!!seq $value") }
                    shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("!!map [$value]") }
                    Yaml.default
                        .parseToYamlNode("!!seq [$value]")
                        .yamlTaggedNode.innerNode.yamlList.items
                        .single()
                        .yamlScalar
                        .toInt() shouldBe value
                }
            }
            test("random document edits return a tree or a located YAML error on both input APIs, seed=$seed") {
                val documents = Arb.element(listOf("{key: [1, true, null]}", "a: &a [value]\nb: *a", "!!seq [one, two]", "- text\n- 'quoted'", "key: |\n  first\n  second\n"))
                checkAll(config, documents, Arb.list(Arb.int(0..255), 1..12)) { original, edits ->
                    var document = original
                    val alphabet = "[]{}:&*!#,'\"\n\t\u0001abc012 ~"
                    for (edit in edits) {
                        val at = edit % (document.length + 1)
                        document =
                            when (edit % 3) {
                                0 -> document.take(at) + alphabet[edit % alphabet.length] + document.drop(at)
                                1 -> document.take(at) + document.drop(at + 1)
                                else -> document.take(at) + alphabet[edit % alphabet.length] + document.drop(at + 1)
                            }
                    }
                    val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 12, codePointLimit = 2048, anchorsAndAliases = AnchorsAndAliases.Permitted()))
                    val fromString = parseOutcome { yaml.parseToYamlNode(document) }
                    val fromSource = parseOutcome { yaml.parseToYamlNode(Buffer().writeUtf8(document)) }
                    fromString shouldBe fromSource
                }
            }
        }
    })

// Catch only the documented parser boundary: runtime errors, stack overflow and assertion failures must fail the property.
private fun parseOutcome(parse: () -> YamlNode): String {
    val node =
        try {
            parse()
        } catch (error: YamlException) {
            (error.line > 0) shouldBe true
            (error.column > 0) shouldBe true
            return "error: ${error::class.simpleName}: ${error.message} at ${error.path}"
        }
    // An encoding failure after a successful parse must not be mistaken for rejected input.
    return "tree: " + Yaml.default.encodeToString<YamlNode>(node)
}
