package example.readwrite

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

@Serializable
data class Service(
    val name: String,
    val port: Int,
    val tags: List<String> = emptyList(),
)

fun main() {
    val input = """
        name: api
        port: 8080
        tags: [internal, kotlin]
    """.trimIndent()

    val yaml = Yaml.default
    val service = yaml.decodeFromString(Service.serializer(), input)
    println(service.name) // api

    val updated = service.copy(port = 9090)
    val output = yaml.encodeToString(Service.serializer(), updated)
    println(output)
}
