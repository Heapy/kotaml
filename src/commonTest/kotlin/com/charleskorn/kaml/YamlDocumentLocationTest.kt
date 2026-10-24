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

class YamlDocumentLocationTest :
    FlatFunSpec({
        listOf(
            "one\n---\ntwo" to Location(2, 1),
            "---\none\n---\ntwo" to Location(3, 1),
            "one\n...\n---\ntwo" to Location(3, 1),
        ).forEach { (document, location) ->
            for (typed in listOf(false, true)) {
                test("reports one-based coordinates for the second document in '$document' with typed=$typed") {
                    val error =
                        shouldThrow<MalformedYamlException> {
                            if (typed) {
                                Yaml.default.decodeFromString<String>(document)
                            } else {
                                Yaml.default.parseToYamlNode(document)
                            }
                        }

                    error.line shouldBe location.line
                    error.column shouldBe location.column
                    error.path shouldBe YamlPath.root.withError(location)
                }
            }
        }
    })
