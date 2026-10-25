package example.json

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.json.toJsonElement
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val input = """
        name: api
        port: 8080
    """.trimIndent()
    val node = Yaml.default.parseToYamlNode(input)
    val json = node.toJsonElement()
    println(json) // {"name":"api","port":8080}
}
