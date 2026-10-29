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

package com.charleskorn.kaml.fuzz

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlNode
import com.code_intelligence.jazzer.junit.FuzzTest
import okio.Buffer
import org.junit.jupiter.api.Timeout

class YamlReaderFuzzTest {
    private val yaml =
        Yaml(
            configuration =
                YamlConfiguration(
                    codePointLimit = MAX_INPUT_BYTES,
                    maxNestingDepth = 20,
                    anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = 64u),
                ),
        )

    @Timeout(10)
    @FuzzTest
    fun read(input: ByteArray) {
        if (input.size > MAX_INPUT_BYTES) return
        // The target is YAML syntax; malformed UTF-8 is replaced consistently before both APIs.
        val document = input.decodeToString()
        val node =
            try {
                yaml.parseToYamlNode(document)
            } catch (error: YamlException) {
                check(error.line > 0 && error.column > 0) { "Unlocated YAML error for ${input.toHexString()}" }
                return
            }
        // Catch only parsing failures above. An inconsistency between successful APIs is a finding.
        val fromSource = yaml.decodeFromSource(YamlNode.serializer(), Buffer().writeUtf8(document))
        check(node.equivalentContentTo(fromSource)) { "String and Source readers disagree for ${input.toHexString()}" }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 4096
    }
}
