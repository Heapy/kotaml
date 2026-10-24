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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.ReaderException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlVersionException
import kotlinx.serialization.decodeFromString
import okio.Buffer
import okio.IOException
import okio.Source
import okio.Timeout
import kotlin.coroutines.cancellation.CancellationException

class YamlParserFailureTest :
    FlatFunSpec({
        for (typed in listOf(false, true)) {
            test("classifies the input limit as malformed YAML with its cause, typed=$typed") {
                val yaml = Yaml(configuration = YamlConfiguration(codePointLimit = 8))
                val error =
                    shouldThrow<MalformedYamlException> {
                        if (typed) yaml.decodeFromString<String>("abcdefghi\n") else yaml.parseToYamlNode("abcdefghi\n")
                    }
                error.cause.shouldBeInstanceOf<YamlEngineException>()
                error.message.shouldContain("8 code points")
                error.line shouldBe 2
                error.column shouldBe 1
            }
            test("classifies invalid controls and preserves their offset, typed=$typed") {
                val error =
                    shouldThrow<MalformedYamlException> {
                        if (typed) Yaml.default.decodeFromString<String>("abc\u0001") else Yaml.default.parseToYamlNode("abc\u0001")
                    }
                val cause = error.cause.shouldBeInstanceOf<ReaderException>()
                cause.codePoint shouldBe 1
                cause.position shouldBe 3
                error.message.shouldContain("U+0001")
                error.message.shouldContain("offset 3")
                // Reader validation happens during look-ahead: the cursor is still at the start.
                error.location shouldBe Location(1, 1)
            }
            test("classifies unsupported YAML version with its cause, typed=$typed") {
                val error =
                    shouldThrow<MalformedYamlException> {
                        val document = "%YAML 2.0\n---\nvalue"
                        if (typed) Yaml.default.decodeFromString<String>(document) else Yaml.default.parseToYamlNode(document)
                    }
                error.cause.shouldBeInstanceOf<YamlVersionException>()
                error.message.shouldContain("Unsupported YAML version")
                error.message.shouldContain("major=2")
                error.message.shouldContain("minor=0")
            }
            test("keeps marked syntax diagnostics and original cause, typed=$typed") {
                val error =
                    shouldThrow<MalformedYamlException> {
                        if (typed) Yaml.default.decodeFromString<List<String>>("[one, two") else Yaml.default.parseToYamlNode("[one, two")
                    }
                val cause = error.cause.shouldBeInstanceOf<MarkedYamlEngineException>()
                val mark = cause.problemMark!!
                error.location shouldBe Location(mark.line + 1, mark.column + 1)
                error.message.shouldContain(cause.problem)
            }
        }
        for (prefix in listOf("", "value: first\n")) {
            for (typed in listOf(false, true)) {
                test("preserves source IOException identity without retry, prefix='$prefix', typed=$typed") {
                    val sentinel = IOException("retryable source failure")
                    val source = ParserFailingSource(prefix, sentinel)
                    val error =
                        shouldThrow<IOException> {
                            if (typed) Yaml.default.decodeFromSource<Map<String, String>>(source) else Yaml.default.parseToYamlNode(source)
                        }
                    error shouldBeSameInstanceAs sentinel
                    source.failures shouldBe 1
                }
            }
        }
        for (failure in listOf(CancellationException("cancelled"), Error("fatal sentinel"), IllegalStateException("source bug"))) {
            test("propagates ${failure::class.simpleName} from the source unchanged") {
                val source = ParserFailingSource("", failure)
                val thrown = shouldThrow<Throwable> { Yaml.default.decodeFromSource<String>(source) }
                thrown shouldBeSameInstanceAs failure
                source.failures shouldBe 1
            }
        }
        test("retains the original public MalformedYamlException constructor") {
            val error = MalformedYamlException("message", YamlPath.root)
            error.message shouldBe "message"
            error.path shouldBe YamlPath.root
            error.cause shouldBe null
        }
    })

private class ParserFailingSource(
    prefix: String,
    private val failure: Throwable,
) : Source {
    private val initial = Buffer().writeUtf8(prefix)
    var failures = 0

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (initial.size > 0) return initial.read(sink, byteCount)
        failures++
        throw failure
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {}
}
