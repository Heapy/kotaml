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
import it.krzeminski.snakeyaml.engine.kmp.nodes.Tag
import it.krzeminski.snakeyaml.engine.kmp.resolver.CoreScalarResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class YamlQuotedScalarContractTest :
    FlatFunSpec({
        val resolver = CoreScalarResolver(supportMerge = false)
        val examples =
            mapOf(
                "0012" to Tag.INT,
                "0x10" to Tag.INT,
                "true" to Tag.BOOL,
                ".nan" to Tag.FLOAT,
                "ordinary" to Tag.STR,
                "99999999999999999999999999999" to Tag.INT,
                "1.2345678901234567890123456789" to Tag.FLOAT,
            )
        val configurations = listOf(null) + SingleLineStringStyle.entries
        for (style in configurations) {
            val yaml = if (style == null) Yaml.default else Yaml(configuration = YamlConfiguration(singleLineStringStyle = style))
            for ((content, plainTag) in examples) {
                val effectiveStyle = style ?: SingleLineStringStyle.DoubleQuoted
                val expectedScalar = quotedContractText(content, effectiveStyle, plainTag != Tag.STR)
                for (shape in listOf("root", "list", "map")) {
                    test("preserves quoted '$content' in $shape with ${style ?: "default"}") {
                        val source =
                            when (shape) {
                                "root" -> "'$content'"
                                "list" -> "['$content']"
                                else -> "key: '$content'"
                            }
                        val expected =
                            when (shape) {
                                "root" -> expectedScalar
                                "list" -> "- $expectedScalar"
                                else -> "${quotedContractText("key", effectiveStyle, false)}: $expectedScalar"
                            } + "\n"
                        val parsed = yaml.parseToYamlNode(source)
                        quotedContractScalar(parsed, shape).plain shouldBe false
                        val output = yaml.encodeToString(YamlNode.serializer(), parsed)
                        output shouldBe expected
                        val scalar = quotedContractScalar(yaml.parseToYamlNode(output), shape)
                        scalar.content shouldBe content
                        val expectedPlain =
                            effectiveStyle == SingleLineStringStyle.Plain ||
                                (effectiveStyle == SingleLineStringStyle.PlainExceptAmbiguous && plainTag == Tag.STR)
                        scalar.plain shouldBe expectedPlain
                        resolver.resolve(scalar.content, implicit = scalar.plain) shouldBe
                            if (effectiveStyle == SingleLineStringStyle.Plain) plainTag else Tag.STR
                    }
                }

                test("property styles override ${style ?: "default"} for quoted '$content' without leaking to siblings") {
                    val scalar = yaml.parseToYamlNode("'$content'").yamlScalar
                    val output = yaml.encodeToString(QuotedContractProperties(scalar, scalar, scalar, scalar, scalar, scalar))
                    val expectations =
                        linkedMapOf(
                            "before" to effectiveStyle,
                            "single" to SingleLineStringStyle.SingleQuoted,
                            "double" to SingleLineStringStyle.DoubleQuoted,
                            "plain" to SingleLineStringStyle.Plain,
                            "ambiguous" to SingleLineStringStyle.PlainExceptAmbiguous,
                            "after" to effectiveStyle,
                        )
                    output shouldBe
                        expectations.entries.joinToString("\n", postfix = "\n") { (key, propertyStyle) ->
                            "$key: ${quotedContractText(content, propertyStyle, plainTag != Tag.STR)}"
                        }
                    val reparsed = yaml.parseToYamlNode(output).yamlMap
                    for ((key, propertyStyle) in expectations) {
                        val actual = reparsed.get<YamlScalar>(key)!!
                        actual.content shouldBe content
                        resolver.resolve(actual.content, implicit = actual.plain) shouldBe
                            if (propertyStyle == SingleLineStringStyle.Plain) plainTag else Tag.STR
                    }
                }
            }
        }
    })

private fun quotedContractText(
    content: String,
    style: SingleLineStringStyle,
    ambiguous: Boolean,
): String =
    when (style) {
        SingleLineStringStyle.SingleQuoted -> "'$content'"
        SingleLineStringStyle.DoubleQuoted -> "\"$content\""
        SingleLineStringStyle.Plain -> content
        SingleLineStringStyle.PlainExceptAmbiguous -> if (ambiguous) "\"$content\"" else content
    }

private fun quotedContractScalar(
    node: YamlNode,
    shape: String,
): YamlScalar =
    when (shape) {
        "root" -> node.yamlScalar
        "list" -> node.yamlList[0].yamlScalar
        else -> node.yamlMap.get<YamlScalar>("key")!!
    }

@Serializable
private data class QuotedContractProperties(
    val before: YamlScalar,
    @YamlSingleLineStringStyle(SingleLineStringStyle.SingleQuoted) val single: YamlScalar,
    @YamlSingleLineStringStyle(SingleLineStringStyle.DoubleQuoted) val double: YamlScalar,
    @YamlSingleLineStringStyle(SingleLineStringStyle.Plain) val plain: YamlScalar,
    @YamlSingleLineStringStyle(SingleLineStringStyle.PlainExceptAmbiguous) val ambiguous: YamlScalar,
    val after: YamlScalar,
)
