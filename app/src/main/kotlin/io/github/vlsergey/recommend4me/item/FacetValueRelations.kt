package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_PARENT
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_SYNONYM
import org.jooq.DSLContext
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * WHAT A SITE SAYS OF THE VALUES OF A FACET BETWEEN THEM, as its source database holds it: which keys are synonyms of
 * which — other names of one value — and which values it puts under which.
 */
interface FacetValueRelations {
    /**
     * The synonyms of the facet's values [synonyms] gives — value key to synonym key to its name — written in place of
     * those the values had, or added to them when not [whole]; each synonym named. A synonym is of one value: given of
     * another, it is of that one now. One writer at a time, as of the names. The synonyms told anew — of no value
     * before, or of another: the synonym's key to the value's key.
     */
    fun setFacetValueSynonyms(
        facet: String,
        synonyms: Map<String, Map<String, String>>,
        whole: Boolean = true,
    ): Map<String, String>

    /** Every synonym of the facet's values: the synonym's key to the value's key. */
    fun facetSynonyms(facet: String): Map<String, String>

    /**
     * Of the keys [synonyms], those that are synonyms of a value of the facet: the synonym's key to the value's key.
     */
    fun synonymsOf(facet: String, synonyms: Collection<String>): Map<String, String>

    /** The synonyms of the facet's values [keys]: a value's key to its synonyms' keys, of those that have any. */
    fun synonymsFor(facet: String, keys: Collection<String>): Map<String, Set<String>>

    /**
     * The values the facet's values are under — value key to the keys above it — written in place of those the values
     * had, or added to them when not [whole]; one writer at a time, as of the names. The pairs told anew: a value's key
     * to a key above it.
     */
    fun setFacetValueParents(
        facet: String,
        parents: Map<String, Collection<String>>,
        whole: Boolean = true,
    ): Set<Pair<String, String>>

    /** The values of the facet above each of [keys]: key to the keys above it, of those under any. */
    fun parentsOf(facet: String, keys: Collection<String>): Map<String, Set<String>>

    /** The values of the facet under each of [keys]: key to the keys under it, of those above any. */
    fun childrenOf(facet: String, keys: Collection<String>): Map<String, Set<String>>
}

/**
 * The [FacetValueRelations] of the source database [db]; the synonyms named in its [dictionary]. One writer at a time,
 * as of the names.
 */
internal class FacetValueRelationRepository(private val db: DSLContext, private val dictionary: FacetDictionary) :
    FacetValueRelations {

    @Synchronized
    override fun setFacetValueSynonyms(
        facet: String,
        synonyms: Map<String, Map<String, String>>,
        whole: Boolean,
    ): Map<String, String> {
        if (synonyms.isEmpty()) return emptyMap()
        val s = FACET_VALUE_SYNONYM
        val told = synonyms.flatMap { (key, of) ->
            of.keys.filter { it != key }.map { it.take(VALUE_KEY_LENGTH) to key.take(VALUE_KEY_LENGTH) }
        }
        val before = synonymsOf(facet, told.map { it.first })
        db.transaction { c ->
            val tx = DSL.using(c)
            if (whole)
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
        dictionary.nameFacetValues(facet, synonyms.values.fold(emptyMap()) { all, names -> all + names })
        return told.filter { (synonym, key) -> before[synonym] != key }.toMap()
    }

    override fun facetSynonyms(facet: String): Map<String, String> =
        db.select(FACET_VALUE_SYNONYM.SYNONYM_KEY, FACET_VALUE_SYNONYM.VALUE_KEY)
            .from(FACET_VALUE_SYNONYM)
            .where(FACET_VALUE_SYNONYM.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** A lookup a key, by the table's key: a work's or a page's few. */
    override fun synonymsOf(facet: String, synonyms: Collection<String>): Map<String, String> =
        synonyms
            .distinct()
            .mapNotNull { synonym ->
                db.select(FACET_VALUE_SYNONYM.VALUE_KEY)
                    .from(FACET_VALUE_SYNONYM)
                    .where(FACET_VALUE_SYNONYM.FACET.eq(facet), FACET_VALUE_SYNONYM.SYNONYM_KEY.eq(synonym))
                    .fetchOne()
                    ?.value1()
                    ?.let { synonym to it }
            }
            .toMap()

    /** A lookup a value, by the index of the values of the synonyms. */
    override fun synonymsFor(facet: String, keys: Collection<String>): Map<String, Set<String>> =
        keys
            .distinct()
            .associateWith { key ->
                db.select(FACET_VALUE_SYNONYM.SYNONYM_KEY)
                    .from(FACET_VALUE_SYNONYM)
                    .where(FACET_VALUE_SYNONYM.FACET.eq(facet), FACET_VALUE_SYNONYM.VALUE_KEY.eq(key))
                    .fetch(FACET_VALUE_SYNONYM.SYNONYM_KEY)
                    .filterNotNull()
                    .toSet()
            }
            .filterValues { it.isNotEmpty() }

    @Synchronized
    override fun setFacetValueParents(
        facet: String,
        parents: Map<String, Collection<String>>,
        whole: Boolean,
    ): Set<Pair<String, String>> {
        if (parents.isEmpty()) return emptySet()
        val p = FACET_VALUE_PARENT
        val told =
            parents
                .flatMap { (key, above) ->
                    above.filter { it != key }.map { key.take(VALUE_KEY_LENGTH) to it.take(VALUE_KEY_LENGTH) }
                }
                .toSet()
        val before = parentsOf(facet, parents.keys.map { it.take(VALUE_KEY_LENGTH) })
        db.transaction { c ->
            val tx = DSL.using(c)
            if (whole)
                tx.deleteFrom(p)
                    .where(p.FACET.eq(facet), p.VALUE_KEY.`in`(parents.keys.map { it.take(VALUE_KEY_LENGTH) }))
                    .execute()
            told
                .filter { (key, parent) -> whole || parent !in before[key].orEmpty() }
                .forEach { (key, parent) ->
                    tx.insertInto(p).set(p.FACET, facet).set(p.VALUE_KEY, key).set(p.PARENT_KEY, parent).execute()
                }
        }
        return told.filter { (key, parent) -> parent !in before[key].orEmpty() }.toSet()
    }

    override fun parentsOf(facet: String, keys: Collection<String>): Map<String, Set<String>> =
        related(facet, keys, FACET_VALUE_PARENT.VALUE_KEY, FACET_VALUE_PARENT.PARENT_KEY)

    override fun childrenOf(facet: String, keys: Collection<String>): Map<String, Set<String>> =
        related(facet, keys, FACET_VALUE_PARENT.PARENT_KEY, FACET_VALUE_PARENT.VALUE_KEY)

    /** Of every one of [keys] in the column [by], the keys of the column [other] beside it: a lookup a key. */
    private fun related(
        facet: String,
        keys: Collection<String>,
        by: TableField<*, String?>,
        other: TableField<*, String?>,
    ): Map<String, Set<String>> =
        keys
            .distinct()
            .associateWith { key ->
                db.select(other)
                    .from(FACET_VALUE_PARENT)
                    .where(FACET_VALUE_PARENT.FACET.eq(facet), by.eq(key))
                    .fetch(other)
                    .filterNotNull()
                    .toSet()
            }
            .filterValues { it.isNotEmpty() }
}
