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

import io.kotest.assertions.withClue
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.Buffer

// One stable corpus and one fresh exploration per run. Kotest prints the seed and shrunk input on failure.
@OptIn(ExperimentalKotest::class)
class YamlRoundTripPropertyTest :
    FlatFunSpec({
        for (seed in listOf(137L, null)) {
            val config = PropTestConfig(iterations = 150, seed = seed ?: PropertyTesting.defaultSeed)
            test("strings round trip across writer styles, seed=$seed") {
                checkAll(config, yamlText, writerConfiguration) { value, configuration ->
                    val yaml = Yaml(configuration = configuration)
                    val document = yaml.encodeToString(value)
                    yaml.decodeFromString<String>(document) shouldBe value
                    yaml.decodeFromSource<String>(Buffer().writeUtf8(document)) shouldBe value
                }
            }
            test("models preserve maps, nulls, defaults and integer boundaries, seed=$seed") {
                checkAll(config, recordArb, writerConfiguration) { value, configuration ->
                    val yaml = Yaml(configuration = configuration)
                    val document = yaml.encodeToString(value)
                    withClue("YAML document:\n$document") {
                        yaml.decodeFromString<PropertyRecord>(document) shouldBe value
                    }
                }
            }
            test("polymorphic payloads round trip with tag and property discriminators, seed=$seed") {
                checkAll(config, payloadArb, Arb.boolean()) { value, property ->
                    val yaml = Yaml(configuration = YamlConfiguration(polymorphismStyle = if (property) PolymorphismStyle.Property else PolymorphismStyle.Tag))
                    yaml.decodeFromString<PropertyPayload>(yaml.encodeToString(value)) shouldBe value
                }
            }
            test("tree relocation preserves content and updates every descendant path, seed=$seed") {
                checkAll(config, recordArb) { value ->
                    val original = Yaml.default.parseToYamlNode(Yaml.default.encodeToString(value))
                    val destination = YamlPath.root.withMapElementKey("grafted", Location(3, 4)).withMapElementValue(Location(3, 13))
                    val relocated = original.withPath(destination)
                    relocated.equivalentContentTo(original) shouldBe true
                    assertRelocated(original, relocated, original.path, destination)
                    val encoded = Yaml.default.encodeToString<YamlNode>(relocated)
                    Yaml.default.decodeFromString<PropertyRecord>(encoded) shouldBe value
                }
            }
            test("signed decimal and unsigned radix spellings preserve integer values, seed=$seed") {
                checkAll(config, Arb.long(), Arb.int(0..8)) { value, zeros ->
                    val sign = if (value < 0) "-" else "+"
                    val decimal = sign + "0".repeat(zeros) + value.toString().removePrefix("-")
                    YamlScalar(decimal, YamlPath.root).toLong() shouldBe value
                    if (value >= 0) {
                        YamlScalar("0x" + value.toString(16), YamlPath.root).toLong() shouldBe value
                        YamlScalar("0o" + value.toString(8), YamlPath.root).toLong() shouldBe value
                    }
                }
            }
        }
    })

private val yamlText: Arb<String> =
    Arb.choice(
        Arb.element(listOf("", "null", "~", "TRUE", "0xFF", "0012", "1e5", "*.html", "!.git", "<<", "---", ": ", "#", "a\r\nb", "a\nb\n", "\u0000", "\u0085", "\u2028", "\u2029", "😀")),
        Arb.list(Arb.element(listOf("a", "Z", "0", " ", "\t", "\n", "\r", "'", "\"", "\\", ":", "#", "[", "]", "{", "}", "*", "&", "!", "é", "я", "漢", "😀")), 0..32).map { it.joinToString("") },
    )

private val writerConfiguration =
    Arb.bind(Arb.int(0..3), Arb.int(0..4), Arb.boolean(), Arb.boolean()) { single, multi, flow, defaults ->
        YamlConfiguration(
            singleLineStringStyle = SingleLineStringStyle.entries[single],
            multiLineStringStyle = MultiLineStringStyle.entries[multi],
            sequenceStyle = if (flow) SequenceStyle.Flow else SequenceStyle.Block,
            encodeDefaults = defaults,
            yamlNamingStrategy = if (defaults) YamlNamingStrategy.SnakeCase else YamlNamingStrategy.KebabCase,
        )
    }

@Serializable
private data class PropertyRecord(
    val displayName: String,
    val revision: Long,
    val attributes: Map<String, List<String?>>,
    val enabled: Boolean = true,
)

private val recordArb =
    Arb.bind(yamlText, Arb.long(), Arb.list(Arb.bind(yamlText, Arb.list(yamlText.orNull(), 0..4)) { key, values -> key to values }, 0..5), Arb.boolean()) { name, revision, entries, enabled ->
        PropertyRecord(name, revision, entries.toMap(), enabled)
    }

@Serializable
private sealed class PropertyPayload {
    @Serializable
    data class Text(
        val content: String,
    ) : PropertyPayload()

    @Serializable
    data class Count(
        val amount: Long,
    ) : PropertyPayload()
}

private val payloadArb: Arb<PropertyPayload> = Arb.choice(yamlText.map { PropertyPayload.Text(it) }, Arb.long().map { PropertyPayload.Count(it) })

private fun assertRelocated(
    original: YamlNode,
    relocated: YamlNode,
    oldRoot: YamlPath,
    newRoot: YamlPath,
) {
    relocated.path.segments shouldBe newRoot.segments + original.path.segments.drop(oldRoot.segments.size)
    when (original) {
        is YamlList -> {
            original.items.zip(relocated.yamlList.items).forEach { (before, after) -> assertRelocated(before, after, oldRoot, newRoot) }
        }

        is YamlMap -> {
            original.entries.entries.zip(relocated.yamlMap.entries.entries).forEach { (before, after) ->
                assertRelocated(before.key, after.key, oldRoot, newRoot)
                assertRelocated(before.value, after.value, oldRoot, newRoot)
            }
        }

        is YamlTaggedNode -> {
            assertRelocated(original.innerNode, relocated.yamlTaggedNode.innerNode, oldRoot, newRoot)
        }

        else -> {}
    }
}
