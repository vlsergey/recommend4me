package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_DESCRIPTION
import org.jooq.DSLContext

/**
 * THE DICTIONARY OF THE FACETS' VALUES of one source, as its source database holds it: what the site names every value
 * and what it says the value means. Which are synonyms of which, and under which: [FacetValueRelations].
 */
interface FacetDictionary {
    /**
     * The names of the facet's values written where new or changed. One writer at a time: a page's reading and the
     * chances made after an answer name the same values at once, and H2's MERGE of two of them is no merge — the second
     * insert breaks the primary key.
     */
    fun nameFacetValues(facet: String, names: Map<String, String>)

    /**
     * The names of the values [keys] of a facet; a key without a name has none here. One pass over the facet's names,
     * the keys looked up here: H2 takes a list of keys bound to the query not for the primary key but for a condition
     * of every row of the facet, every key of it compared with every row — of the thousands of characters most of the
     * time of reading what a model learns.
     */
    fun facetNames(facet: String, keys: Collection<String>): Map<String, String>

    /** Every named value of one facet: key to name. */
    fun facetNames(facet: String): Map<String, String>

    /** The keys of the facet's values named each of the [names], by the index of the names: name to its keys. */
    fun keysNamed(facet: String, names: Collection<String>): Map<String, List<String>>

    /** Every named value of every facet: facet to key to name. */
    fun allFacetNames(): Map<String, Map<String, String>>

    /**
     * What the site says the facet's values mean, written where new or changed; one writer at a time, as of the names.
     */
    fun describeFacetValues(facet: String, descriptions: Map<String, String>)

    /** What the site says the values [keys] of a facet mean: key to description. */
    fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String>
}

/** The [FacetDictionary] of the source database [db]; one writer at a time of its names and descriptions. */
internal class FacetDictionaryRepository(private val db: DSLContext) : FacetDictionary {

    @Synchronized
    override fun nameFacetValues(facet: String, names: Map<String, String>) {
        if (names.isEmpty()) return
        val known = facetNames(facet, names.keys)
        names
            .filter { (key, name) -> known[key] != name }
            .forEach { (key, name) ->
                db.insertInto(FACET_VALUE)
                    .set(FACET_VALUE.FACET, facet)
                    .set(FACET_VALUE.VALUE_KEY, key.take(VALUE_KEY_LENGTH))
                    .set(FACET_VALUE.NAME, name.take(NAME_LENGTH))
                    .onDuplicateKeyUpdate()
                    .set(FACET_VALUE.NAME, name.take(NAME_LENGTH))
                    .execute()
            }
    }

    /**
     * The names of the facet's values [keys]: of a few — a work's values written — each by the table's key, a lookup
     * apiece; of more, every row of the facet read in one pass and the keys picked out of it — H2 matches a long list
     * of keys against every row of the facet rather than by the key. A pass for a work written was a pass over every
     * author and tag of the site a work: the import of a million works slower with every one (user, 2026-11-17).
     */
    override fun facetNames(facet: String, keys: Collection<String>): Map<String, String> =
        when {
            keys.isEmpty() -> emptyMap()
            keys.size <= LOOKED_UP ->
                keys
                    .distinct()
                    .mapNotNull { key ->
                        db.select(FACET_VALUE.NAME)
                            .from(FACET_VALUE)
                            .where(FACET_VALUE.FACET.eq(facet), FACET_VALUE.VALUE_KEY.eq(key))
                            .fetchOne()
                            ?.value1()
                            ?.let { key to it }
                    }
                    .toMap()
            else -> namesInOnePass(facet, keys as? Set<String> ?: keys.toHashSet())
        }

    /** The names of the facet's values [wanted], every row of the facet read in one pass. */
    private fun namesInOnePass(facet: String, wanted: Set<String>): Map<String, String> {
        val out = HashMap<String, String>()
        db.select(FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME)
            .from(FACET_VALUE)
            .where(FACET_VALUE.FACET.eq(facet))
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor -> cursor.forEach { if (it.value1()!! in wanted) out[it.value1()!!] = it.value2()!! } }
        return out
    }

    override fun facetNames(facet: String): Map<String, String> =
        db.select(FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME)
            .from(FACET_VALUE)
            .where(FACET_VALUE.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** One lookup a name, by the index of the names: H2 matches a list bound to the query against every row. */
    override fun keysNamed(facet: String, names: Collection<String>): Map<String, List<String>> =
        names
            .distinct()
            .associateWith { name ->
                db.select(FACET_VALUE.VALUE_KEY)
                    .from(FACET_VALUE)
                    .where(FACET_VALUE.FACET.eq(facet), FACET_VALUE.NAME.eq(name.take(NAME_LENGTH)))
                    .fetch(FACET_VALUE.VALUE_KEY)
                    .filterNotNull()
            }
            .filterValues { it.isNotEmpty() }

    override fun allFacetNames(): Map<String, Map<String, String>> =
        db.select(FACET_VALUE.FACET, FACET_VALUE.VALUE_KEY, FACET_VALUE.NAME)
            .from(FACET_VALUE)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! })
            .mapValues { it.value.toMap() }

    @Synchronized
    override fun describeFacetValues(facet: String, descriptions: Map<String, String>) {
        if (descriptions.isEmpty()) return
        val d = FACET_VALUE_DESCRIPTION
        val known = facetDescriptions(facet, descriptions.keys)
        descriptions
            .filter { (key, text) -> known[key] != text }
            .forEach { (key, text) ->
                db.insertInto(d)
                    .set(d.FACET, facet)
                    .set(d.VALUE_KEY, key.take(VALUE_KEY_LENGTH))
                    .set(d.DESCRIPTION, text.take(DESCRIPTION_LENGTH))
                    .onDuplicateKeyUpdate()
                    .set(d.DESCRIPTION, text.take(DESCRIPTION_LENGTH))
                    .execute()
            }
    }

    override fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val d = FACET_VALUE_DESCRIPTION
        return keys
            .distinct()
            .chunked(ITEM_BATCH)
            .flatMap { chunk ->
                db.select(d.VALUE_KEY, d.DESCRIPTION).from(d).where(d.FACET.eq(facet), d.VALUE_KEY.`in`(chunk)).fetch {
                    it.value1()!! to it.value2()!!
                }
            }
            .toMap()
    }

    private companion object {
        /** The longest description of a value the table holds (`VARCHAR(4000)`). */
        const val DESCRIPTION_LENGTH = 4000

        /**
         * The keys whose names are looked up one by one, by the table's key: a work's values; of more, one pass over
         * the facet is fewer reads than a lookup a key ([facetNames]).
         */
        const val LOOKED_UP = 50
    }
}
