package example.tree

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar

fun main() {
    val input = """
        leader: Amy
        members:
          - Bob
          - Cindy
          - Dan
    """.trimIndent()
    val result = Yaml.default.parseToYamlNode(input)
    println(result.yamlMap.get<YamlList>("members")!![1].yamlScalar.content)
}
