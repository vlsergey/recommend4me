package io.github.vlsergey.recommend4me.universe

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * WIKIDATA as the catalogue of fictional universes: a universe is any item fan fiction is written
 * about — a franchise, a series, a work, a fictional universe — and its characters are whatever is
 * said to be in it (P1441 "present in work", P1080 "from narrative universe", P8345 "media
 * franchise", or listed by it with P674 "characters"), of it or of any part of it down the parts
 * (P179 "part of the series", P361 "part of", P8345). Not only people: creatures and places a
 * story is about are its characters too.
 *
 * Every name is asked for in the user's languages: the label and every alias, so that "Невилл
 * Лонгботтом" of the fans is found as an alias of "Невилл Долгопупс" of the translation.
 */
class WikidataCatalogue(private val json: JsonMapper) : UniverseCatalogue {
    override val id = "wikidata"
    override val title = "Викиданные"

    private val http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build()

    /**
     * By the names and aliases of the items in every language first — what a short name like
     * "Гарри Поттер" is — then by their whole text, which finds a long one like "Роулинг Джоан
     * «Гарри Поттер»".
     */
    override fun findUniverses(name: String, languages: List<String>): List<UniverseEntry> {
        val byName = languages.flatMap { lang ->
            get("$API?action=wbsearchentities&type=item&limit=$SEARCHED&format=json&language=$lang&uselang=$lang&search=${enc(name)}")
                .path("search").mapNotNull { it.path("id").asString(null) }
        }
        val byText = get("$API?action=query&list=search&srnamespace=0&srlimit=$SEARCHED&format=json&srsearch=${enc(name)}")
            .path("query").path("search").mapNotNull { it.path("title").asString(null) }
        return entries((byName + byText).distinct(), languages)
    }

    override fun universe(id: String, languages: List<String>): UniverseEntry? = entries(listOf(id), languages).firstOrNull()

    override fun characters(id: String, languages: List<String>): List<UniverseCharacter> {
        require(ITEM.matches(id)) { "Not an item of Wikidata: $id" }
        val langs = languages.joinToString(",") { "\"$it\"" }
        val query = """
            SELECT ?c ?kind ?text WHERE {
              # Two subqueries, each evaluated by itself: within one, the union of the two ways
              # makes the service walk the parts for every character there is
              { SELECT DISTINCT ?c WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345)* wd:$id . ?c (wdt:P1441|wdt:P1080|wdt:P8345) ?part . } }
              UNION
              { SELECT DISTINCT ?c WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345)* wd:$id . ?part wdt:P674 ?c . } }
              { ?c rdfs:label ?text . BIND("label" AS ?kind) }
              UNION { ?c skos:altLabel ?text . BIND("alias" AS ?kind) }
              UNION { ?c schema:description ?text . BIND("description" AS ?kind) }
              FILTER(LANG(?text) IN ($langs))
            }
        """.trimIndent()
        class Names(val labels: MutableMap<String, String> = HashMap(), val aliases: MutableList<Pair<String, String>> = ArrayList(), val descriptions: MutableMap<String, String> = HashMap())
        val byCharacter = LinkedHashMap<String, Names>()
        get("$SPARQL?format=json&query=${enc(query)}").path("results").path("bindings").forEach { row ->
            val character = row.path("c").path("value").asString().substringAfterLast('/')
            val text = row.path("text")
            val lang = text.path("xml:lang").asString("")
            val value = text.path("value").asString()
            val names = byCharacter.getOrPut(character) { Names() }
            when (row.path("kind").path("value").asString()) {
                "label" -> names.labels[lang] = value
                "alias" -> names.aliases += lang to value
                "description" -> names.descriptions[lang] = value
            }
        }
        return byCharacter.map { (character, n) ->
            val ordered = languages.mapNotNull { n.labels[it] } + languages.flatMap { lang -> n.aliases.filter { it.first == lang }.map { it.second } }
            UniverseCharacter(character, ordered.distinct(), languages.firstNotNullOfOrNull { n.descriptions[it] }, "$PAGE$character")
        }.filter { it.names.isNotEmpty() }
    }

    /** The items [ids] with their names and descriptions in the first of [languages] that has one, in the order given. */
    private fun entries(ids: List<String>, languages: List<String>): List<UniverseEntry> {
        val items = ids.filter { ITEM.matches(it) }
        if (items.isEmpty()) return emptyList()
        val entities = get(
            "$API?action=wbgetentities&props=labels%7Cdescriptions&format=json&ids=${items.joinToString("%7C")}&languages=${languages.joinToString("%7C")}",
        ).path("entities")
        return items.mapNotNull { id ->
            val e = entities.path(id)
            if (e.isMissingNode || e.has("missing")) return@mapNotNull null
            fun first(field: String) = languages.firstNotNullOfOrNull { e.path(field).path(it).path("value").asString(null) }
            UniverseEntry(id, first("labels") ?: id, first("descriptions"), "$PAGE$id")
        }
    }

    private fun get(url: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Wikidata answered ${response.statusCode()}" }
        return json.readTree(response.body())
    }

    companion object {
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val SPARQL = "https://query.wikidata.org/sparql"
        private const val PAGE = "https://www.wikidata.org/wiki/"

        /** Entries a search answers with: a page of them for the user to pick from. */
        private const val SEARCHED = 10

        /** The Wikimedia user-agent policy: who asks, and where to find them. */
        private const val USER_AGENT = "recommend4me (https://github.com/vlsergey/recommend4me)"

        private val TIMEOUT: Duration = Duration.ofSeconds(60)
        private val ITEM = Regex("Q\\d+")

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
