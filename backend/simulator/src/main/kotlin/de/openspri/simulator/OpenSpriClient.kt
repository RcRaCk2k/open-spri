package de.openspri.simulator

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Elements of a JSON array (empty for anything else). */
fun JsonNode.items(): List<JsonNode> = if (isArray) (0 until size()).map { get(it) } else emptyList()

/** A REST call of our instance failed: status and problem details, for the test report. */
class ApiException(val status: Int, val body: String, request: String) : RuntimeException("$request → HTTP $status: $body")

/**
 * Our instance under test, driven only through its public REST API `/api/v1` (principle 4): exactly what an
 * external system or the frontend does.
 */
class OpenSpriClient(private val baseUrl: URI, private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = JsonMapper.builder().build()
    val nodes: JsonNodeFactory = JsonNodeFactory.instance

    fun get(path: String): JsonNode = call("GET", path, null)
    fun post(path: String, body: JsonNode? = null): JsonNode = call("POST", path, body)
    fun put(path: String, body: JsonNode): JsonNode = call("PUT", path, body)

    fun call(method: String, path: String, body: JsonNode?): JsonNode {
        val request = HttpRequest.newBuilder(baseUrl.resolve(path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw ApiException(response.statusCode(), response.body(), "$method $path")
        return if (response.body().isBlank()) nodes.nullNode() else json.readTree(response.body())
    }

    // --- convenience for the scenarios --------------------------------------------------------------

    fun order(id: Long): JsonNode = get("/api/v1/orders/$id").path("order")

    fun history(id: Long): List<JsonNode> = get("/api/v1/orders/$id/history").items()

    /** Our orders with this external order number in [role]; newest first. */
    fun findOrders(role: String, externalOrderNumber: String): List<JsonNode> =
        get("/api/v1/orders?role=$role&q=${encode(externalOrderNumber)}&limit=10").items().filter { it.path("externalOrderNumber").stringValue() == externalOrderNumber }

    fun codes(codes: List<String>): JsonNode = nodes.arrayNode().apply { codes.forEach { addObject().put("code", it) } }

    fun objectNode(): ObjectNode = nodes.objectNode()

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}
