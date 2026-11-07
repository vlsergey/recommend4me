package io.github.vlsergey.recommend4me.universe

/**
 * The characters of a universe as Wikidata says them — whatever is said to be in it or in any part of it — each with
 * its names and descriptions in the user's languages, its classes, its sex, the statements asked and what it is based
 * on; the versions of a character not entries of their own ([WikidataCatalogue.withoutVersions]).
 */
internal class WikidataCharacters(private val client: WikidataClient) {

    fun of(id: String, languages: List<String>, properties: Set<String>): List<UniverseCharacter> {
        require(ITEM.matches(id)) { "Not an item of Wikidata: $id" }
        val ids = entriesOf(id)
        if (ids.isEmpty()) return emptyList()
        val entries = "VALUES ?c { ${ids.joinToString(" ") { "wd:$it" }} }"
        // What each entry is (P31 "instance of"), asked by itself: joined to the names, it multiplies
        // them. OPTIONAL keeps the order — the entries first, then their classes: a plain join lets
        // the service start from every P31 statement there is and run out of time
        val classes =
            itemsOf("SELECT ?c ?class WHERE { $entries OPTIONAL { ?c wdt:P31 ?class . } }", "class") { ArrayList() }
        // The sex or gender of each entry (P21), asked by itself the same way
        val sexes = itemsOf("SELECT ?c ?sex WHERE { $entries OPTIONAL { ?c wdt:P21 ?sex . } }", "sex") { HashSet() }
        // What each entry is based on (P144), asked by itself the same way: a character's version in a film or a
        // musical
        val basedOn =
            itemsOf("SELECT ?c ?original WHERE { $entries ?c wdt:P144 ?original . }", "original") { HashSet() }
        val byCharacter = names(entries, languages)
        val stated = client.statements(entries, properties)
        val read =
            byCharacter
                .map { (character, n) ->
                    UniverseCharacter(
                        character,
                        n.labels,
                        n.aliases.groupBy({ it.first }, { it.second }),
                        n.descriptions,
                        "$PAGE$character",
                        languages,
                        classes[character].orEmpty(),
                        sexes[character]?.let(::sexOf),
                        stated[character].orEmpty(),
                    )
                }
                .filter { it.names.isNotEmpty() }
        return WikidataCatalogue.withoutVersions(read, basedOn)
    }

    /**
     * The entries of the universe [id]: whatever is said to be in it or in any of its parts. The parts of the universe
     * are walked once as its own statements are (P1434 "takes place in fictional universe" too: a character only of a
     * work set in the universe is one of its characters), then its entries once from them; every later query is given
     * the entries as a list. A walk repeated in each query makes the service walk it each time, and a universe with
     * hundreds of works set in it then runs out of time.
     */
    private fun entriesOf(id: String): List<String> {
        val parts =
            client
                .sparql("SELECT DISTINCT ?part WHERE { ?part (wdt:P179|wdt:P361|wdt:P8345|wdt:P1434)* wd:$id . }")
                .map { it.path("part").path("value").asString().substringAfterLast('/') }
                .filter { ITEM.matches(it) }
        val partList = parts.joinToString(" ") { "wd:$it" }
        // Two subqueries, each evaluated by itself: within one, the union of the two ways makes
        // the service join them for every character there is
        return client
            .sparql(
                """
            SELECT DISTINCT ?c WHERE {
              { SELECT DISTINCT ?c WHERE { VALUES ?part { $partList } ?c (wdt:P1441|wdt:P1080|wdt:P8345) ?part . } }
              UNION
              { SELECT DISTINCT ?c WHERE { VALUES ?part { $partList } ?part wdt:P674 ?c . } }
            }
            """
                    .trimIndent()
            )
            .map { it.path("c").path("value").asString().substringAfterLast('/') }
            .filter { ITEM.matches(it) }
            .distinct()
    }

    /**
     * The items the [query] answers for each entry `?c` in its column [column] — its classes, its sexes, what it is
     * based on — gathered into a collection [empty] makes.
     */
    private fun <C : MutableCollection<String>> itemsOf(query: String, column: String, empty: () -> C): Map<String, C> {
        val out = HashMap<String, C>()
        client.sparql(query).forEach { row ->
            val item = row.path(column).path("value").asString("").substringAfterLast('/')
            if (ITEM.matches(item))
                out.getOrPut(row.path("c").path("value").asString().substringAfterLast('/'), empty) += item
        }
        return out
    }

    /** The labels, the aliases and the descriptions of the entries [entries] in [languages], by entry. */
    private fun names(entries: String, languages: List<String>): Map<String, Names> {
        val langs = languages.joinToString(",") { "\"$it\"" }
        val query =
            """
            SELECT ?c ?kind ?text WHERE {
              $entries
              { ?c rdfs:label ?text . BIND("label" AS ?kind) }
              UNION { ?c skos:altLabel ?text . BIND("alias" AS ?kind) }
              UNION { ?c schema:description ?text . BIND("description" AS ?kind) }
              FILTER(LANG(?text) IN ($langs))
            }
        """
                .trimIndent()
        val byCharacter = LinkedHashMap<String, Names>()
        client.sparql(query).forEach { row ->
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
        return byCharacter
    }

    /** The names of an entry as the query service answers them. */
    private class Names(
        val labels: MutableMap<String, String> = HashMap(),
        val aliases: MutableList<Pair<String, String>> = ArrayList(),
        val descriptions: MutableMap<String, String> = HashMap(),
    )

    private companion object {
        private const val PAGE = "https://www.wikidata.org/wiki/"
        private val ITEM = Regex("Q\\d+")

        /** The values of P21 that say male or female: of a person, and of an organism. */
        private val SEXES =
            mapOf(
                "Q6581097" to CharacterSex.MALE,
                "Q44148" to CharacterSex.MALE,
                "Q6581072" to CharacterSex.FEMALE,
                "Q43445" to CharacterSex.FEMALE,
            )

        /** One sex of the values of P21: male or female when that is all they say, anything else otherwise. */
        private fun sexOf(values: Set<String>): CharacterSex {
            val read = values.map { SEXES[it] ?: CharacterSex.OTHER }.toSet()
            return read.singleOrNull() ?: CharacterSex.OTHER
        }
    }
}
