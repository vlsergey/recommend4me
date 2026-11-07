package io.github.vlsergey.recommend4me.universe

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The requests of the catalogue to Wikidata — to its API and to its query service — sent again when Wikidata asks to
 * wait; and the statements of entries, as the query service answers them.
 */
internal class WikidataClient(
    private val json: JsonMapper,
    /** The query service's address. */
    private val sparqlUrl: String,
) {
    private val http =
        HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

    /** The answer of the API at [url]. */
    fun get(url: String): JsonNode {
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build()
        return json.readTree(send(request))
    }

    /**
     * The rows a query of the query service answers; sent in the body, as a list of hundreds of items does not fit an
     * address.
     */
    fun sparql(query: String): List<JsonNode> {
        val request =
            HttpRequest.newBuilder(URI.create(sparqlUrl))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("query=${enc(query)}"))
                .build()
        return json.readTree(send(request)).path("results").path("bindings").toList()
    }

    /**
     * The statements of [properties] of every entry [entries] binds to `?c` — a list of them, or a subquery selecting
     * them — asked by themselves as the classes are: entry to property to its values, an item by its id, any other
     * value as its text. A list is joined as it is: wrapped in a subquery of its own, thousands of entries take the
     * service most of a minute.
     */
    fun statements(entries: String, properties: Set<String>): Map<String, Map<String, List<String>>> {
        val asked = properties.filter { PROPERTY.matches(it) }.distinct()
        if (asked.isEmpty()) return emptyMap()
        val values = asked.joinToString(" ") { "wdt:$it" }
        val out = HashMap<String, MutableMap<String, MutableList<String>>>()
        sparql("SELECT ?c ?p ?v WHERE { $entries VALUES ?p { $values } ?c ?p ?v . }").forEach { row ->
            val character = row.path("c").path("value").asString().substringAfterLast('/')
            val property = row.path("p").path("value").asString().substringAfterLast('/')
            val v = row.path("v")
            val value = v.path("value").asString()
            val shown =
                if (v.path("type").asString("") == "uri" && value.startsWith(ENTITY)) value.removePrefix(ENTITY)
                else value
            out.getOrPut(character) { HashMap() }.getOrPut(property) { ArrayList() } += shown
        }
        return out
    }

    /**
     * The body of the answer to [request]. Asked too often, Wikidata answers 429 with how long to wait (Retry-After):
     * the request is sent again after that, for as long as the waits add up to no more than [TIMEOUT]; an answer of 429
     * without the header, or past it, fails as any other.
     */
    private fun send(request: HttpRequest): String {
        var waited = Duration.ZERO
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == HttpURLConnection.HTTP_OK) return response.body()
            val wait =
                response
                    .takeIf { it.statusCode() == TOO_MANY_REQUESTS }
                    ?.headers()
                    ?.firstValue("Retry-After")
                    ?.orElse(null)
                    ?.let(::retryAfter)
            check(wait != null && waited + wait <= TIMEOUT) { "Wikidata answered ${response.statusCode()}" }
            Thread.sleep(wait.toMillis())
            waited += wait
        }
    }

    /** Retry-After as seconds, or as the date to retry at; null when it is neither. */
    private fun retryAfter(value: String): Duration? =
        value.trim().toLongOrNull()?.let { Duration.ofSeconds(it.coerceAtLeast(0)) }
            ?: runCatching {
                Duration.between(
                        Instant.now(),
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME),
                    )
                    .coerceAtLeast(Duration.ZERO)
            }
                .getOrNull()

    companion object {
        /** The Wikimedia user-agent policy: who asks, and where to find them. */
        private const val USER_AGENT = "recommend4me (https://github.com/vlsergey/recommend4me)"

        private val TIMEOUT: Duration = Duration.ofSeconds(60)

        /** HTTP's "Too Many Requests": Wikidata asks to wait. */
        private const val TOO_MANY_REQUESTS = 429

        /** A property of Wikidata: "P8419". */
        private val PROPERTY = Regex("P\\d+")

        /** The address an item of Wikidata is given by in the answers of the query service. */
        private const val ENTITY = "http://www.wikidata.org/entity/"

        /** [s] encoded for an address or a form's body. */
        fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
