package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.scanned
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_DESCRIPTION
import java.sql.ResultSet
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

    /** The names of the facet's values [wanted] — of every value, when null —, of one pass over the table ([scan]). */
    private fun namesInOnePass(facet: String, wanted: Set<String>?): Map<String, String> {
        val out = HashMap<String, String>()
        scan("SELECT facet, value_key, name FROM facet_value") { r ->
            val key = r.getString("value_key")
            if (r.getString("facet") == facet && (wanted == null || key in wanted)) out[key] = r.getString("name")
        }
        return out
    }

    /**
     * Every row of the table [sql] reads, handed to [row]: the table's own rows in the order they are kept, no
     * condition and no order of a key — by JDBC, not jOOQ's records. A table keyed by text keeps its rows by a number
     * of its own: a reading by its key, or by a facet, takes them a row at a time by that number, a seek of the disk
     * each (2026-11-23: the descriptions of every site's fandom by their keys, 6 s of every request of the universes
     * alike); read whole, they come page after page. Read whole by the statement, not lazily: a lazy reading of H2 as
     * the site's pages write the table lost a part of the file under it ("Chunk not found").
     */
    private fun scan(sql: String, row: (ResultSet) -> Unit) = db.scanned(sql, row)

    override fun facetNames(facet: String): Map<String, String> = namesInOnePass(facet, null)

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

    override fun allFacetNames(): Map<String, Map<String, String>> {
        val out = HashMap<String, HashMap<String, String>>()
        scan("SELECT facet, value_key, name FROM facet_value") { r ->
            out.getOrPut(r.getString("facet")) { HashMap() }[r.getString("value_key")] = r.getString("name")
        }
        return out
    }

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

    /**
     * What the site says the values [keys] of the facet mean: of a few, each by the table's key; of more, every row of
     * the table read in one pass ([scan]) and the keys picked out of it.
     */
    override fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String> {
        val d = FACET_VALUE_DESCRIPTION
        return when {
            keys.isEmpty() -> emptyMap()
            keys.size <= LOOKED_UP ->
                keys
                    .distinct()
                    .mapNotNull { key ->
                        db.select(d.DESCRIPTION)
                            .from(d)
                            .where(d.FACET.eq(facet), d.VALUE_KEY.eq(key))
                            .fetchOne()
                            ?.value1()
                            ?.let { key to it }
                    }
                    .toMap()
            else -> {
                val wanted = keys as? Set<String> ?: keys.toHashSet()
                val out = HashMap<String, String>()
                scan("SELECT facet, value_key, description FROM facet_value_description") { r ->
                    val key = r.getString("value_key")
                    if (r.getString("facet") == facet && key in wanted) out[key] = r.getString("description")
                }
                out
            }
        }
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
