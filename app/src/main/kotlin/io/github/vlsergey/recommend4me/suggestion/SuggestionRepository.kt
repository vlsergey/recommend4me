package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTER
import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTION
import io.github.vlsergey.recommend4me.database.model.tables.references.FACET_SUGGESTION_BASIS
import io.github.vlsergey.recommend4me.database.source.tables.references.FACET_VALUE_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import java.time.Instant

/** The fitted state of a facet's suggester. */
class StoredSuggester(val suggester: String, val fittedAt: Instant, val feedback: Int, val content: ByteArray)

/** A value worked out for an item: [suggested] — it lacks it and should have it; otherwise it has it and should not. */
data class Suggestion(val itemId: String, val facet: String, val key: String, val suggested: Boolean, val chance: Double)

/** What an item's suggestions were made of, and the suggestions, of one source, in the model database of its content type. */
class SuggestionRepository(private val db: DSLContext, private val source: String) {

    fun suggester(facet: String): StoredSuggester? =
        db.selectFrom(FACET_SUGGESTER).where(FACET_SUGGESTER.SOURCE.eq(source), FACET_SUGGESTER.FACET.eq(facet)).fetchOne()
            ?.let { StoredSuggester(it.suggester!!, it.fittedAt!!, it.feedback!!, it.content!!) }

    fun saveSuggester(facet: String, suggester: String, fittedAt: Instant, feedback: Int, content: ByteArray) {
        db.insertInto(FACET_SUGGESTER)
            .set(FACET_SUGGESTER.SOURCE, source).set(FACET_SUGGESTER.FACET, facet).set(FACET_SUGGESTER.SUGGESTER, suggester)
            .set(FACET_SUGGESTER.FITTED_AT, fittedAt).set(FACET_SUGGESTER.FEEDBACK, feedback).set(FACET_SUGGESTER.CONTENT, content)
            .onDuplicateKeyUpdate()
            .set(FACET_SUGGESTER.SUGGESTER, suggester).set(FACET_SUGGESTER.FITTED_AT, fittedAt)
            .set(FACET_SUGGESTER.FEEDBACK, feedback).set(FACET_SUGGESTER.CONTENT, content)
            .execute()
    }

    /** The fingerprint the suggestions of every item of the facet were made from. */
    fun fingerprints(facet: String): Map<String, Long> =
        db.select(FACET_SUGGESTION_BASIS.ITEM_ID, FACET_SUGGESTION_BASIS.FINGERPRINT).from(FACET_SUGGESTION_BASIS)
            .where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.FACET.eq(facet))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    fun fingerprint(itemId: String, facet: String): Long? =
        db.select(FACET_SUGGESTION_BASIS.FINGERPRINT).from(FACET_SUGGESTION_BASIS)
            .where(FACET_SUGGESTION_BASIS.SOURCE.eq(source), FACET_SUGGESTION_BASIS.ITEM_ID.eq(itemId), FACET_SUGGESTION_BASIS.FACET.eq(facet))
            .fetchOne()?.value1()

    /** Replaces the suggestions of the items [made] (item to its fingerprint) of the facet with [suggestions]. */
    fun replace(facet: String, made: Map<String, Long>, suggestions: List<Suggestion>, now: Instant) {
        if (made.isEmpty()) return
        db.transaction { tx ->
            val t = tx.dsl()
            made.keys.chunked(BATCH).forEach { chunk ->
                t.deleteFrom(FACET_SUGGESTION)
                    .where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.FACET.eq(facet), FACET_SUGGESTION.ITEM_ID.`in`(chunk))
                    .execute()
            }
            suggestions.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { s ->
                    t.insertInto(FACET_SUGGESTION)
                        .set(FACET_SUGGESTION.SOURCE, source).set(FACET_SUGGESTION.ITEM_ID, s.itemId).set(FACET_SUGGESTION.FACET, facet)
                        .set(FACET_SUGGESTION.VALUE_KEY, s.key.take(500)).set(FACET_SUGGESTION.KIND, if (s.suggested) SUGGESTED else DOUBTED)
                        .set(FACET_SUGGESTION.CHANCE, s.chance)
                }).execute()
            }
            made.entries.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (id, fingerprint) ->
                    t.insertInto(FACET_SUGGESTION_BASIS)
                        .set(FACET_SUGGESTION_BASIS.SOURCE, source).set(FACET_SUGGESTION_BASIS.ITEM_ID, id).set(FACET_SUGGESTION_BASIS.FACET, facet)
                        .set(FACET_SUGGESTION_BASIS.FINGERPRINT, fingerprint).set(FACET_SUGGESTION_BASIS.MADE_AT, now)
                        .onDuplicateKeyUpdate()
                        .set(FACET_SUGGESTION_BASIS.FINGERPRINT, fingerprint).set(FACET_SUGGESTION_BASIS.MADE_AT, now)
                }).execute()
            }
        }
    }

    /** The suggestions of the items [ids], every facet. */
    fun ofItems(ids: Collection<String>): Map<String, List<Suggestion>> {
        val out = HashMap<String, MutableList<Suggestion>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(FACET_SUGGESTION.ITEM_ID, FACET_SUGGESTION.FACET, FACET_SUGGESTION.VALUE_KEY, FACET_SUGGESTION.KIND, FACET_SUGGESTION.CHANCE)
                .from(FACET_SUGGESTION)
                .where(FACET_SUGGESTION.SOURCE.eq(source), FACET_SUGGESTION.ITEM_ID.`in`(chunk))
                .orderBy(FACET_SUGGESTION.CHANCE.desc())
                .fetch { r -> out.getOrPut(r.value1()!!) { ArrayList() } += Suggestion(r.value1()!!, r.value2()!!, r.value3()!!, r.value4() == SUGGESTED, r.value5()!!) }
        }
        return out
    }

    companion object {
        private const val BATCH = 1000
        private const val SUGGESTED = "S"
        private const val DOUBTED = "D"
    }
}

/** The vectors of the names of facet values of one source, as queries, in its source database. */
class ValueNameRepository(private val db: DSLContext) {

    /** The stored vectors of the facet's values by [encoder], with the hash of the name each was made of. */
    fun of(facet: String, encoder: String): Map<String, Pair<String, FloatArray>> =
        db.select(FACET_VALUE_VECTOR.VALUE_KEY, FACET_VALUE_VECTOR.NAME_HASH, FACET_VALUE_VECTOR.VEC).from(FACET_VALUE_VECTOR)
            .where(FACET_VALUE_VECTOR.FACET.eq(facet), FACET_VALUE_VECTOR.ENCODER.eq(encoder))
            .fetchMap({ it.value1()!! }, { it.value2()!! to Vectors.fromHalf(it.value3()!!) })

    fun save(facet: String, encoder: String, rows: List<Triple<String, String, FloatArray>>) {
        rows.chunked(1000).forEach { chunk ->
            db.batch(chunk.map { (key, hash, vector) ->
                val packed = Vectors.half(vector)
                db.insertInto(FACET_VALUE_VECTOR)
                    .set(FACET_VALUE_VECTOR.FACET, facet).set(FACET_VALUE_VECTOR.VALUE_KEY, key.take(500))
                    .set(FACET_VALUE_VECTOR.ENCODER, encoder).set(FACET_VALUE_VECTOR.NAME_HASH, hash).set(FACET_VALUE_VECTOR.VEC, packed)
                    .onDuplicateKeyUpdate()
                    .set(FACET_VALUE_VECTOR.ENCODER, encoder).set(FACET_VALUE_VECTOR.NAME_HASH, hash).set(FACET_VALUE_VECTOR.VEC, packed)
            }).execute()
        }
    }
}
