package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_DESCRIPTION
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_SYNONYM
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE DICTIONARY OF THE FACETS' VALUES of one source, as its source database holds it: what the site names every value,
 * what it says the value means, and which keys are synonyms of which.
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

    /** Every named value of every facet: facet to key to name. */
    fun allFacetNames(): Map<String, Map<String, String>>

    /**
     * What the site says the facet's values mean, written where new or changed; one writer at a time, as of the names.
     */
    fun describeFacetValues(facet: String, descriptions: Map<String, String>)

    /** What the site says the values [keys] of a facet mean: key to description. */
    fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String>

    /**
     * The synonyms of the facet's values [synonyms] gives — value key to synonym key to its name — written in place of
     * those the values had; each synonym named. A synonym is of one value: given of another, it is of that one now. One
     * writer at a time, as of the names.
     */
    fun setFacetValueSynonyms(facet: String, synonyms: Map<String, Map<String, String>>)

    /** Every synonym of the facet's values: the synonym's key to the value's key. */
    fun facetSynonyms(facet: String): Map<String, String>
}

/** The [FacetDictionary] of the source database [db]; one writer at a time of its names, descriptions and synonyms. */
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

    override fun facetNames(facet: String, keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val wanted = keys as? Set<String> ?: keys.toHashSet()
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

    @Synchronized
    override fun setFacetValueSynonyms(facet: String, synonyms: Map<String, Map<String, String>>) {
        if (synonyms.isEmpty()) return
        val s = FACET_VALUE_SYNONYM
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(s)
                .where(s.FACET.eq(facet), s.VALUE_KEY.`in`(synonyms.keys.map { it.take(VALUE_KEY_LENGTH) }))
                .execute()
            synonyms.forEach { (key, of) ->
                of.keys
                    .filter { it != key }
                    .forEach { synonym ->
                        tx.insertInto(s)
                            .set(s.FACET, facet)
                            .set(s.SYNONYM_KEY, synonym.take(VALUE_KEY_LENGTH))
                            .set(s.VALUE_KEY, key.take(VALUE_KEY_LENGTH))
                            .onDuplicateKeyUpdate()
                            .set(s.VALUE_KEY, key.take(VALUE_KEY_LENGTH))
                            .execute()
                    }
            }
        }
        nameFacetValues(facet, synonyms.values.fold(emptyMap()) { all, names -> all + names })
    }

    override fun facetSynonyms(facet: String): Map<String, String> =
        db.select(FACET_VALUE_SYNONYM.SYNONYM_KEY, FACET_VALUE_SYNONYM.VALUE_KEY)
            .from(FACET_VALUE_SYNONYM)
            .where(FACET_VALUE_SYNONYM.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    private companion object {
        /** The longest name of a value the table holds (`VARCHAR(1000)`). */
        const val NAME_LENGTH = 1000

        /** The longest description of a value the table holds (`VARCHAR(4000)`). */
        const val DESCRIPTION_LENGTH = 4000
    }
}
