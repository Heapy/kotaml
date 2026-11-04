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
import kotlinx.serialization.decodeFromString

class YamlEmptyMapKeyReadingTest :
    FlatFunSpec({
        listOf(
            Triple("block", "? \n: 1", Location(1, 2)),
            Triple("flow", "{? : 1}", Location(1, 3)),
        ).forEach { (style, input, location) ->
            test("rejects an empty plain $style key") {
                val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }

                exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
                exception.path shouldBe YamlPath.root.withError(location)
            }
        }

        test("reports the path and location of an empty key in a nested mapping") {
            val input = "outer:\n  ?\n  : 1"
            val parentPath = YamlPath.root.withMapElementKey("outer", Location(1, 1)).withMapElementValue(Location(2, 3))
            val location = Location(2, 4)
            val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }
            exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
            exception.path shouldBe parentPath.withError(location)
        }

        test("reports the path and location of an empty key inside a sequence") {
            val input = "- ?\n  : 1"
            val parentPath = YamlPath.root.withListEntry(0, Location(1, 3))
            val location = Location(1, 4)
            val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }
            exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
            exception.path shouldBe parentPath.withError(location)
        }

        val yaml = Yaml.default

        for (key in listOf("\"\"", "''", "!!str", "!", "!<tag:yaml.org,2002:str>")) {
            test("reads the explicit empty string key '$key'") {
                val input = "? $key\n: 1"
                yaml.decodeFromString<Map<String, Int>>(input) shouldBe mapOf("" to 1)
                yaml
                    .parseToYamlNode(input)
                    .yamlMap.entries.keys
                    .single()
                    .plain shouldBe false
            }
        }

        test("keeps empty mapping and sequence values null") {
            yaml.decodeFromString<Map<String, String?>>("value:") shouldBe mapOf("value" to null)
            yaml.decodeFromString<List<String?>>("-") shouldBe listOf(null)
        }
    })
