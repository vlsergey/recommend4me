package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.tag.TagCatalogue
import io.github.vlsergey.recommend4me.tag.TagEntry
import io.github.vlsergey.recommend4me.universe.WikidataClient.Companion.enc
import tools.jackson.databind.json.JsonMapper

/**
 * WIKIDATA as the catalogue of fictional universes: a universe is any item fan fiction is written about — a franchise,
 * a series, a work, a fictional universe — and its characters are whatever is said to be in it (P1441 "present in
 * work", P1080 "from narrative universe", P8345 "media franchise", or listed by it with P674 "characters"), of it or of
 * any part of it down the parts (P179 "part of the series", P361 "part of", P8345). Not only people are said to be in a
 * universe: its places, groups, spells and things are too. Every entry comes with its classes (P31 "instance of"), and
 * a class tells whether its entries are characters, for the user to keep the characters and whatever else they want.
 * Its sex or gender (P21) comes with it too. A version of a character of the universe — based on it (P144), of its sex
 * and going by its name, as Gwen Stacy of the films is of Gwen Stacy of the comics — is not an entry of its own
 * ([withoutVersions]).
 *
 * Every name is asked for in the user's languages: the label and every alias, so that "Невилл Лонгботтом" of the fans
 * is found as an alias of "Невилл Долгопупс" of the translation — and the labels and descriptions are answered in each
 * of them that has them.
 */
class WikidataCatalogue(
    json: JsonMapper,
    /** The API's address: Wikidata's own, another only in the tests. */
    private val api: String = API,
    /** The query service's address. */
    sparqlUrl: String = SPARQL,
) : UniverseCatalogue, TagCatalogue {
    override val id = "wikidata"

    private val client = WikidataClient(json, sparqlUrl)
    private val characters = WikidataCharacters(client)

    /**
     * An item a tag stands for — "Quidditch", "angst", "slow burn" — by its names and aliases in every language; a
     * Q-id, or the address of an item's page, is that item.
     */
    override fun find(text: String, languages: List<String>): List<TagEntry> {
        val asked = ITEM_IN.find(text.trim())?.value
        if (asked != null) return entries(listOf(asked), languages)
        return entries(searched(text.trim(), languages).distinct(), languages)
    }

    /** The items the API's search by names and aliases finds of [text], in every one of [languages]. */
    private fun searched(text: String, languages: List<String>): List<String> = languages.flatMap { lang ->
        client
            .get(
                "$api?action=wbsearchentities&type=item&limit=$SEARCHED&format=json" +
                    "&language=$lang&uselang=$lang&search=${enc(text)}"
            )
            .path("search")
            .mapNotNull { it.path("id").asString(null) }
    }

    /**
     * The items [ids] with their labels, aliases and descriptions in every one of [languages] that has them, in the
     * order given.
     */
    override fun entries(ids: Collection<String>, languages: List<String>): List<TagEntry> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        return items.chunked(ENTITIES_ASKED).flatMap { chunk ->
            val entities =
                client
                    .get(
                        "$api?action=wbgetentities&props=labels%7Caliases%7Cdescriptions&format=json" +
                            "&ids=${chunk.joinToString("%7C")}&languages=${languages.joinToString("%7C")}"
                    )
                    .path("entities")
            chunk.mapNotNull { id ->
                val e = entities.path(id)
                if (e.isMissingNode || e.has("missing")) return@mapNotNull null
                fun byLanguage(field: String) =
                    languages
                        .mapNotNull { lang ->
                            e.path(field).path(lang).path("value").asString(null)?.let { lang to it }
                        }
                        .toMap()
                val aliases =
                    languages
                        .associateWith { lang ->
                            e.path("aliases").path(lang).mapNotNull { it.path("value").asString(null) }
                        }
                        .filterValues { it.isNotEmpty() }
                TagEntry(id, byLanguage("labels"), aliases, byLanguage("descriptions"), "$PAGE$id")
            }
        }
    }

    /**
     * The items right above each item: its classes (P279 "subclass of") and, of a fictional thing, what it is the
     * fictional analog of (P1074) — "quidditch" is a fictional sport, and of "sport" only through its analog. Never its
     * P31 "instance of": "satire" is an instance of "literary genre", which says what kind of item it is, not what it
     * is a kind of. Deprecated statements left out.
     */
    override fun above(ids: Collection<String>): Map<String, List<String>> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        return items
            .chunked(ENTITIES_ASKED)
            .flatMap { chunk ->
                val entities =
                    client
                        .get("$api?action=wbgetentities&props=claims&format=json&ids=${chunk.joinToString("%7C")}")
                        .path("entities")
                chunk.mapNotNull { id ->
                    val claims = entities.path(id).path("claims")
                    val parents =
                        ABOVE.flatMap { p ->
                                claims
                                    .path(p)
                                    .filter { it.path("rank").asString("normal") != "deprecated" }
                                    .mapNotNull {
                                        it.path("mainsnak").path("datavalue").path("value").path("id").asString(null)
                                    }
                            }
                            .distinct()
                            .filter { it != id }
                    parents.takeIf { it.isNotEmpty() }?.let { id to it }
                }
            }
            .toMap()
    }

    /**
     * By the names and aliases of the items in every language first — what a short name like "Гарри Поттер" is — then
     * by their whole text, which finds a long one like "Роулинг Джоан «Гарри Поттер»".
     */
    override fun findUniverses(name: String, languages: List<String>): List<UniverseEntry> {
        val byName = searched(name, languages)
        val byText =
            client
                .get("$api?action=query&list=search&srnamespace=0&srlimit=$SEARCHED&format=json&srsearch=${enc(name)}")
                .path("query")
                .path("search")
                .mapNotNull { it.path("title").asString(null) }
        return universeEntries((byName + byText).distinct(), languages)
    }

    override fun universe(id: String, languages: List<String>, properties: Set<String>): UniverseEntry? {
        val entry = universeEntries(listOf(id), languages).firstOrNull() ?: return null
        // The statements of the universe and of every work, series and franchise within it or set in it
        // (P1434 "takes place in fictional universe": the series of a universe of its own) — but its
        // characters', which are said to be in it the same way
        val stated =
            client.statements(
                "{ SELECT DISTINCT ?c WHERE { ?c (wdt:P179|wdt:P361|wdt:P8345|wdt:P1434)* wd:$id . " +
                    "FILTER NOT EXISTS { ?c wdt:P31/wdt:P279* wd:$CHARACTER } } }",
                properties,
            )
        val merged = HashMap<String, MutableList<String>>()
        stated.values.forEach { byProperty ->
            byProperty.forEach { (p, values) -> merged.getOrPut(p) { ArrayList() } += values }
        }
        return UniverseEntry(
            entry.id,
            entry.labels,
            entry.descriptions,
            entry.url,
            entry.languages,
            merged.mapValues { it.value.distinct() },
        )
    }

    override fun characters(id: String, languages: List<String>, properties: Set<String>): List<UniverseCharacter> =
        characters.of(id, languages, properties)

    /**
     * A class is of characters when "character" (Q95074) is among its superclasses (P279, any steps up). Every
     * superclass of every class is asked for in one query and the answer is found here: up from the classes the paths
     * are short, while a path to a given class from hundreds of them makes the service search every way down and run
     * out of time.
     */
    override fun classes(ids: Collection<String>, languages: List<String>): List<UniverseClass> {
        val items = ids.filter { ITEM.matches(it) }.distinct()
        if (items.isEmpty()) return emptyList()
        val langs = languages.joinToString(",") { "\"$it\"" }
        val characters = HashSet<String>()
        val names = HashMap<String, MutableMap<String, String>>()
        items.chunked(CLASSES_ASKED).forEach { chunk ->
            val values = chunk.joinToString(" ") { "wd:$it" }
            client
                .sparql("SELECT ?class ?super WHERE { VALUES ?class { $values } ?class wdt:P279* ?super . }")
                .forEach { row ->
                    if (row.path("super").path("value").asString().substringAfterLast('/') == CHARACTER) {
                        characters += row.path("class").path("value").asString().substringAfterLast('/')
                    }
                }
            client
                .sparql(
                    "SELECT ?class ?name WHERE { VALUES ?class { $values } ?class rdfs:label ?name . " +
                        "FILTER(LANG(?name) IN ($langs)) }"
                )
                .forEach { row ->
                    val name = row.path("name")
                    names
                        .getOrPut(row.path("class").path("value").asString().substringAfterLast('/')) { HashMap() }[
                            name.path("xml:lang").asString("")] = name.path("value").asString()
                }
        }
        return items.map { id -> UniverseClass(id, names[id].orEmpty(), id in characters, languages) }
    }

    /**
     * The items [ids] with their names and descriptions in every one of [languages] that has them, in the order given.
     */
    private fun universeEntries(ids: List<String>, languages: List<String>): List<UniverseEntry> {
        val items = ids.filter { ITEM.matches(it) }
        if (items.isEmpty()) return emptyList()
        val entities =
            client
                .get(
                    "$api?action=wbgetentities&props=labels%7Cdescriptions&format=json" +
                        "&ids=${items.joinToString("%7C")}&languages=${languages.joinToString("%7C")}"
                )
                .path("entities")
        return items.mapNotNull { id ->
            val e = entities.path(id)
            if (e.isMissingNode || e.has("missing")) return@mapNotNull null
            fun byLanguage(field: String) =
                languages
                    .mapNotNull { lang -> e.path(field).path(lang).path("value").asString(null)?.let { lang to it } }
                    .toMap()
            UniverseEntry(id, byLanguage("labels"), byLanguage("descriptions"), "$PAGE$id", languages)
        }
    }

    companion object {
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val SPARQL = "https://query.wikidata.org/sparql"
        private const val PAGE = "https://www.wikidata.org/wiki/"

        /** "character": the class every class of characters is a subclass of. */
        private const val CHARACTER = "Q95074"

        /** Classes asked for in one query: the length of a request, not meaning. */
        private const val CLASSES_ASKED = 500

        /** Entries a search answers with: a page of them for the user to pick from. */
        private const val SEARCHED = 10

        private val ITEM = Regex("Q\\d+")

        /** An item's id within a text: itself, or the address of its page. */
        private val ITEM_IN = Regex("(?<![A-Za-z0-9])Q\\d+(?![0-9])")

        /** Items asked for in one request: the API's limit. */
        private const val ENTITIES_ASKED = 50

        /** What an item is a kind of ([above]): P279 "subclass of", P1074 "fictional analog of". */
        private val ABOVE = listOf("P279", "P1074")

        /**
         * The [characters] without the versions of a character of the same universe (user, 2026-10-20): an entry based
         * on (P144, [basedOn]) another entry of [characters] of the same sex — or of a sex one of them does not state —
         * a label of it being a name of that one, is the same character for the user, and the one it is based on stands
         * for it: Gwen Stacy of the films based on Gwen Stacy of the comics, Harry Potter of a musical based on Harry
         * Potter of the books. Every name of a version becomes a name of the one standing for it — of the first not a
         * version itself, up a chain of versions — found by any of them.
         */
        internal fun withoutVersions(
            characters: List<UniverseCharacter>,
            basedOn: Map<String, Set<String>>,
        ): List<UniverseCharacter> {
            val byId = characters.associateBy { it.id }
            val versionOf = versionsOf(characters, byId, basedOn)
            // The names of the versions, by language, to the entry standing for each — a circle of versions stands for
            // none
            val gained = HashMap<String, MutableMap<String, MutableList<String>>>()
            versionOf.keys.forEach { id ->
                val into = standing(versionOf, id) ?: return@forEach
                val version = byId.getValue(id)
                val names = gained.getOrPut(into) { LinkedHashMap() }
                version.labels.forEach { (lang, name) -> names.getOrPut(lang) { ArrayList() } += name }
                version.aliases.forEach { (lang, aliases) -> names.getOrPut(lang) { ArrayList() } += aliases }
            }
            val circled = versionOf.keys.filter { standing(versionOf, it) == null }.toSet()
            return characters
                .filter { it.id !in versionOf || it.id in circled }
                .map { c -> gained[c.id]?.let { withNames(c, it) } ?: c }
        }

        private fun namesOf(c: UniverseCharacter) =
            (c.labels.values + c.aliases.values.flatten()).map { it.lowercase() }.toSet()

        /**
         * Each version among [characters] to the entry it is a version of: the first it is based on ([basedOn]) that is
         * another entry, of its sex — or of a sex one of them does not state — and named by one of its labels.
         */
        private fun versionsOf(
            characters: List<UniverseCharacter>,
            byId: Map<String, UniverseCharacter>,
            basedOn: Map<String, Set<String>>,
        ): Map<String, String> =
            characters
                .mapNotNull { c ->
                    basedOn[c.id]
                        .orEmpty()
                        .firstOrNull { id ->
                            val original = byId[id] ?: return@firstOrNull false
                            original.id != c.id &&
                                (original.sex == c.sex || original.sex == null || c.sex == null) &&
                                namesOf(original).let { names -> c.labels.values.any { it.lowercase() in names } }
                        }
                        ?.let { c.id to it }
                }
                .toMap()

        /** The entry standing for [id]: the first up its chain of versions not a version itself; none in a circle. */
        private fun standing(versionOf: Map<String, String>, id: String): String? {
            val seen = HashSet<String>()
            var at = id
            while (true) {
                if (!seen.add(at)) return null
                at = versionOf[at] ?: return at
            }
        }

        /** [c] with every name of [more], by language, it is not known by yet as an alias of its own. */
        private fun withNames(c: UniverseCharacter, more: Map<String, List<String>>): UniverseCharacter {
            val known = namesOf(c).toMutableSet()
            val aliases = LinkedHashMap(c.aliases.mapValues { it.value.toMutableList() })
            more.forEach { (lang, names) ->
                names.forEach { name ->
                    if (known.add(name.lowercase())) aliases.getOrPut(lang) { ArrayList() } += name
                }
            }
            return UniverseCharacter(
                c.id,
                c.labels,
                aliases,
                c.descriptions,
                c.url,
                c.languages,
                c.classes,
                c.sex,
                c.properties,
            )
        }
    }
}
