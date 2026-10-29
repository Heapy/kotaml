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

package com.charleskorn.kaml.json

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlList
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

@OptIn(ExperimentalSerializationApi::class, ExperimentalKotest::class)
class JsonConversionPropertyTest :
    FunSpec({
        for (seed in listOf(267L, null)) {
            val config = PropTestConfig(iterations = 150, seed = seed ?: PropertyTesting.defaultSeed)
            test("JSON trees preserve types and exact numeric spelling through YAML nodes, seed=$seed") {
                checkAll(config, jsonTree(3)) { original ->
                    val yaml = original.toYamlNode()
                    yaml.toJsonElement().toString() shouldBe original.toString()
                    assertJsonPaths(original, yaml, "")
                    val written = Yaml.default.encodeToString<YamlNode>(yaml)
                    Yaml.default
                        .parseToYamlNode(written)
                        .toJsonElement()
                        .toString() shouldBe original.toString()
                }
            }
            test("quoted numeric text remains a string while plain text resolves as a number, seed=$seed") {
                checkAll(config, jsonNumber) { text ->
                    YamlScalar(text, YamlPath.root, plain = false).toJsonElement() shouldBe JsonPrimitive(text)
                    YamlScalar(text, YamlPath.root, plain = true).toJsonElement().toString() shouldBe text
                }
            }
            test("decimal normalization removes only redundant sign and leading zeros, seed=$seed") {
                checkAll(config, Arb.long(1..Long.MAX_VALUE), Arb.int(1..12), Arb.boolean()) { value, zeros, negative ->
                    val text = (if (negative) "-" else "+") + "0".repeat(zeros) + value
                    val expected = if (negative) -value else value
                    YamlScalar(text, YamlPath.root).toJsonElement().toString() shouldBe expected.toString()
                }
            }
            test("non-finite policy propagates through nested collections, seed=$seed") {
                checkAll(config, Arb.element(listOf(".inf", "-.inf", ".nan", ".INF", ".NaN")), Arb.int(0..8)) { literal, wrappers ->
                    val document = "[".repeat(wrappers) + literal + "]".repeat(wrappers)
                    val node = Yaml.default.parseToYamlNode(document)
                    shouldThrow<NonFiniteNumberException> { node.toJsonElement() }.content shouldBe literal
                    node.toJsonElement(NonFiniteNumbers.AS_STRING).toString() shouldBe "[".repeat(wrappers) + JsonPrimitive(literal) + "]".repeat(wrappers)
                    node.toJsonElement(NonFiniteNumbers.AS_NULL).toString() shouldBe "[".repeat(wrappers) + "null" + "]".repeat(wrappers)
                }
            }
        }
    })

// Construct valid JSON numbers as text, including values beyond Long/Double precision.
@OptIn(ExperimentalSerializationApi::class)
private val jsonNumber =
    Arb.bind(Arb.long(), Arb.int(0..999999), Arb.int(-200..200)) { whole, fraction, exponent ->
        "$whole.${fraction.toString().padStart(6, '0')}e$exponent"
    }

private val jsonText =
    Arb.choice(
        Arb.element(listOf("", "true", "null", "001", "0xFF", "<<", "a.b", "[0]", "\u0000", "\r\n", "😀")),
        Arb.list(Arb.element(listOf("a", "b", "0", " ", "\n", "\"", "\\", "é", "漢", "😀")), 0..20).map { it.joinToString("") },
    )

@OptIn(ExperimentalSerializationApi::class)
private fun jsonTree(depth: Int): Arb<JsonElement> {
    val scalar: Arb<JsonElement> =
        Arb.choice(
            Arb.constant(JsonNull),
            jsonText.map { JsonPrimitive(it) },
            Arb.boolean().map { JsonPrimitive(it) },
            Arb.long().map { JsonPrimitive(it) },
            jsonNumber.map { JsonUnquotedLiteral(it) },
        )
    if (depth == 0) return scalar
    val child = jsonTree(depth - 1)
    return Arb.choice(
        scalar,
        Arb.list(child, 0..4).map { JsonArray(it) },
        Arb.list(Arb.bind(jsonText, child) { key, value -> key to value }, 0..4).map { JsonObject(it.toMap()) },
    )
}

private fun assertJsonPaths(
    json: JsonElement,
    yaml: YamlNode,
    expected: String,
) {
    yaml.path.toHumanReadableString() shouldBe expected.ifEmpty { "<root>" }
    when (json) {
        is JsonObject -> {
            json.forEach { (key, value) ->
                val child =
                    yaml.yamlMap.entries.entries
                        .single { it.key.content == key }
                child.key.plain shouldBe false
                assertJsonPaths(value, child.value, if (expected.isEmpty()) key else "$expected.$key")
            }
        }

        is JsonArray -> {
            json.forEachIndexed { index, value ->
                assertJsonPaths(value, yaml.yamlList.items[index], if (expected.isEmpty()) "[$index]" else "$expected[$index]")
            }
        }

        is JsonPrimitive -> {
            if (json !== JsonNull) yaml.yamlScalar.plain shouldBe !json.isString
        }
    }
}
