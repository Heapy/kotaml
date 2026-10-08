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
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

@OptIn(ExperimentalSerializationApi::class)
class JsonToYamlTest :
    FunSpec({
        fun parse(json: String) = Json.parseToJsonElement(json)

        fun encode(element: JsonElement) = Json.encodeToString(JsonElement.serializer(), element)

        test("converts JSON null to a YAML null node") {
            // JsonNull is itself a JsonPrimitive, so a wrong branch order here would make it a scalar.
            JsonNull.toYamlNode().shouldBeInstanceOf<YamlNull>()
        }

        test("keeps a JSON string a quoted scalar") {
            val node = JsonPrimitive("123").toYamlNode()

            node shouldBe YamlScalar("123", YamlPath.root, plain = false)
            node.shouldBeInstanceOf<YamlScalar>().plain shouldBe false
        }

        test("keeps a JSON number a plain scalar carrying its exact text") {
            val node = JsonUnquotedLiteral("1.10").toYamlNode()

            node.shouldBeInstanceOf<YamlScalar>().plain shouldBe true
            node.content shouldBe "1.10"
        }

        test("keeps a JSON boolean a plain scalar") {
            JsonPrimitive(true).toYamlNode().shouldBeInstanceOf<YamlScalar>().plain shouldBe true
        }

        test("converts an array to a list") {
            val node = parse("""[1,"a"]""").toYamlNode()

            node.shouldBeInstanceOf<YamlList>().items.size shouldBe 2
        }

        test("converts an object to a map with quoted keys") {
            val node = parse("""{"a":1}""").toYamlNode().shouldBeInstanceOf<YamlMap>()
            val key = node.entries.keys.single()

            key.content shouldBe "a"
            key.plain shouldBe false
            node.getScalar("a")!!.content shouldBe "1"
        }

        test("builds paths that name the position in the tree") {
            val node = parse("""{"server":{"ports":[80]}}""").toYamlNode()
            val ports = node.yamlMap.get<YamlMap>("server")!!.get<YamlList>("ports")!!

            ports[0].path.toHumanReadableString() shouldBe "server.ports[0]"
        }

        // The round trip is exact for anything a JSON parser can produce.
        listOf(
            "null",
            "true",
            "false",
            "0",
            "-0",
            "123",
            "-123",
            "1e5",
            "1.10",
            "1.00E+003",
            "-0.00e-000",
            "123456789012345678901234",
            """"123"""",
            """"true"""",
            """"null"""",
            """""""",
            """"hello"""",
            "[]",
            "{}",
            """[1,"a",null,true]""",
            """{"name":"123","port":8080,"ratio":1.10,"nothing":null,"items":["a",2,{"nested":{"key":"value"}}]}""",
        ).forEach { json ->
            test("round trips $json through the YAML node tree unchanged") {
                encode(parse(json).toYamlNode().toJsonElement()) shouldBe json
            }
        }

        test("turns an unquoted literal that is not valid JSON into a string on the way back") {
            // JsonUnquotedLiteral accepts any text, so this is the one case the round trip changes.
            encode(JsonUnquotedLiteral("hello").toYamlNode().toJsonElement()) shouldBe """"hello""""
        }

        test("carries a parsed YAML document through JSON and back to an equivalent tree") {
            val yaml =
                """
                name: "123"
                port: 8080
                ratio: 1.10
                enabled: true
                nothing: null
                items:
                  - a
                  - 2
                """.trimIndent()

            val original = Yaml.default.parseToYamlNode(yaml)
            val returned = original.toJsonElement().toYamlNode()

            // Paths and locations are synthesized, so compare the content rather than the nodes.
            returned.equivalentContentTo(original) shouldBe true
        }
    })
