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

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable

class YamlCommentWritingTest :
    FlatFunSpec({
        test("preserves indentation in separate comment arguments") {
            Yaml.default.encodeToString(IndentedCommentLines.serializer(), IndentedCommentLines(true)) shouldBe
                """
                # Example:
                #   parent:
                #     child: value
                enabled: true
                """.trimIndent() + "\n"
        }
        test("splits LF line endings without removing indentation") {
            Yaml.default.encodeToString(MultilineComment.serializer(), MultilineComment(true)) shouldBe
                """
                # Example:
                #   parent:
                #     child: value
                enabled: true
                """.trimIndent() + "\n"
        }
        test("splits CRLF and CR line endings") {
            Yaml.default.encodeToString(CarriageReturnComment.serializer(), CarriageReturnComment(true)) shouldBe
                """
                # first
                # second
                # third
                enabled: true
                """.trimIndent() + "\n"
        }
        test("preserves blank lines and trailing spaces") {
            Yaml.default.encodeToString(BlankLineComment.serializer(), BlankLineComment(true)) shouldBe
                """
                #${" "}
                # first${"  "}
                #${" "}
                # second
                #${" "}
                enabled: true
                """.trimIndent() + "\n"
        }
        test("preserves raw multiline string whitespace") {
            Yaml.default.encodeToString(RawMultilineComment.serializer(), RawMultilineComment(true)) shouldBe
                """
                #${" "}
                #         Example:
                #           parent:
                #             child: value
                #${" ".repeat(9)}
                enabled: true
                """.trimIndent() + "\n"
        }
        test("keeps literal backslash n as comment text") {
            Yaml.default.encodeToString(LiteralBackslashComment.serializer(), LiteralBackslashComment(true)) shouldBe
                """
                # first\nsecond
                enabled: true
                """.trimIndent() + "\n"
        }
    })

@Serializable
private data class IndentedCommentLines(
    @YamlComment("Example:", "  parent:", "    child: value")
    val enabled: Boolean,
)

@Serializable
private data class MultilineComment(
    @YamlComment("Example:\n  parent:\n    child: value")
    val enabled: Boolean,
)

@Serializable
private data class CarriageReturnComment(
    @YamlComment("first\r\nsecond\rthird")
    val enabled: Boolean,
)

@Serializable
private data class BlankLineComment(
    @YamlComment("\nfirst  \n\nsecond\n")
    val enabled: Boolean,
)

@Serializable
private data class RawMultilineComment(
    @YamlComment(
        """
        Example:
          parent:
            child: value
        """,
    )
    val enabled: Boolean,
)

@Serializable
private data class LiteralBackslashComment(
    @YamlComment("first\\nsecond")
    val enabled: Boolean,
)
