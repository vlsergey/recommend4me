package io.github.vlsergey.recommend4me.universe

/**
 * THE DICTIONARY OF A CONTENT TYPE LOOKED UP BY THE STATEMENTS OF ITS CATALOGUE ([CatalogueLookup]) AND BY THE SITES'
 * FANDOMS, read whole at the start of one operation and handed to every part of it — never kept past it. A universe is
 * found of its entries' statements: the universes bound to the entry stating it.
 */
class DictionaryLookup
private constructor(
    /** Universe or character statements: (catalogue, property, value lowercased) to the values stating it. */
    private val universes: Map<Triple<String, String, String>, Set<String>>,
    private val characters: Map<Triple<String, String, String>, Set<String>>,
    /** The universes of the dictionary every character is of, of the universes whose characters are read. */
    val characterUniverses: Map<String, Set<String>>,
    private val dictionary: FandomDictionary,
) : CatalogueLookup {

    override fun universes(catalogue: String, property: String, value: String): Set<String> =
        universes[Triple(catalogue, property, value.lowercase())].orEmpty()

    override fun characters(catalogue: String, property: String, value: String): Set<String> =
        characters[Triple(catalogue, property, value.lowercase())].orEmpty()

    /**
     * The universes of the site's fandom [key] of [source]: the universe it is bound to and every universe above it;
     * none of a fandom not bound yet.
     */
    fun universesOf(source: String, key: String): List<String> {
        val id = dictionary.ofSite(source, key) ?: return emptyList()
        return (listOf(id) + dictionary.ancestors(id)).map(FandomRepository::value)
    }

    /** Whether the dictionary states anything: with nothing stated, no work has a draft of the catalogue. */
    val empty: Boolean
        get() = universes.isEmpty() && characters.isEmpty()

    companion object {
        fun load(repository: UniverseRepository): DictionaryLookup {
            val dictionary = repository.dictionary()
            fun index(statements: List<Triple<String, String, String>>) =
                statements
                    .groupBy(
                        { (entry, property, value) -> Triple(catalogueOf(entry), property, value.lowercase()) },
                        { it.first },
                    )
                    .mapValues { it.value.toSet() }
            // An entry's statement finds the universes bound to it; an entry of no universe, none
            val universes =
                index(repository.universeStatements())
                    .mapValues { (_, entries) -> entries.flatMap(dictionary::ofEntry).toSet() }
                    .filterValues { it.isNotEmpty() }
            return DictionaryLookup(
                universes,
                index(repository.characterStatements()),
                dictionary.universesOfCharacters(repository.characterEntries()),
                dictionary,
            )
        }

        /** The catalogue of a value of the dictionary: "wikidata" of "wikidata:Q174009". */
        private fun catalogueOf(value: String): String = value.substringBefore(':')
    }
}
