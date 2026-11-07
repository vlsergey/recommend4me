package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_WINDOW
import io.github.vlsergey.recommend4me.database.streamed
import io.github.vlsergey.recommend4me.vector.Vectors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The vectors of the texts of one source's items, kept with the hash of the text and the encoder that made them: a text
 * is encoded again when it or the encoder changed, and vectors of another encoder are never read. The windows of the
 * long texts are kept by [windows]; the vectors of short texts — reviews, phrases — by [phrases].
 */
class TextVectorRepository(private val db: DSLContext) {

    /**
     * Held while a vector is written: for milliseconds, not for an encoding. The texts of an item refreshed after a
     * page and the source's whole refresh may write the same text together, and H2's MERGE of the second one meets the
     * first one's row, which its own check did not see.
     */
    private val writing = ReentrantLock()

    /** The windows of the long texts, written under the same lock. */
    val windows = TextWindowRepository(db, writing)

    /** The vectors of short texts by their keys, written under the same lock. */
    val phrases = PhraseVectorRepository(db, writing)

    /** The hash of every stored vector of [encoder]: (item, text key) to hash. */
    fun hashes(encoder: String, itemId: String? = null): Map<Pair<String, String>, String> =
        db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY, TEXT_VECTOR.TEXT_HASH)
            .from(TEXT_VECTOR)
            .where(TEXT_VECTOR.ENCODER.eq(encoder))
            .and(itemId?.let { TEXT_VECTOR.ITEM_ID.eq(it) } ?: DSL.noCondition())
            .fetch()
            .associate { (it.value1()!! to it.value2()!!) to it.value3()!! }

    /** Every stored (item, text key), of any encoder. */
    fun keys(itemId: String? = null): Set<Pair<String, String>> =
        db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY)
            .from(TEXT_VECTOR)
            .where(itemId?.let { TEXT_VECTOR.ITEM_ID.eq(it) } ?: DSL.noCondition())
            .fetchSet { it.value1()!! to it.value2()!! }

    /**
     * The text's vector; the windows of the text it had before are of another text, and go
     * ([TextWindowRepository.saveWindows] makes them anew).
     */
    fun save(itemId: String, textKey: String, encoder: String, hash: String, vector: FloatArray) {
        val packed = Vectors.half(vector)
        writing.withLock {
            db.transaction { c ->
                val tx = c.dsl()
                tx.insertInto(TEXT_VECTOR)
                    .set(TEXT_VECTOR.ITEM_ID, itemId)
                    .set(TEXT_VECTOR.TEXT_KEY, textKey)
                    .set(TEXT_VECTOR.ENCODER, encoder)
                    .set(TEXT_VECTOR.TEXT_HASH, hash)
                    .set(TEXT_VECTOR.VEC, packed)
                    .onDuplicateKeyUpdate()
                    .set(TEXT_VECTOR.ENCODER, encoder)
                    .set(TEXT_VECTOR.TEXT_HASH, hash)
                    .set(TEXT_VECTOR.VEC, packed)
                    .setNull(TEXT_VECTOR.WINDOWS)
                    .execute()
                tx.deleteFrom(TEXT_WINDOW)
                    .where(TEXT_WINDOW.ITEM_ID.eq(itemId), TEXT_WINDOW.TEXT_KEY.eq(textKey))
                    .execute()
            }
        }
    }

    fun delete(itemId: String, textKey: String) {
        db.deleteFrom(TEXT_WINDOW).where(TEXT_WINDOW.ITEM_ID.eq(itemId), TEXT_WINDOW.TEXT_KEY.eq(textKey)).execute()
        db.deleteFrom(TEXT_VECTOR).where(TEXT_VECTOR.ITEM_ID.eq(itemId), TEXT_VECTOR.TEXT_KEY.eq(textKey)).execute()
    }

    /** Vectors of the items [ids] by [encoder]: item to text key to vector. */
    fun ofMany(ids: Collection<String>, encoder: String): Map<String, Map<String, FloatArray>> =
        halvesOf(ids, encoder).mapValues { (_, own) -> own.mapValues { Vectors.fromHalf(it.value) } }

    /** [ofMany] as stored: every vector its half-precision numbers, little-endian ([Vectors.fromHalf]). */
    fun halvesOf(ids: Collection<String>, encoder: String): Map<String, Map<String, ByteArray>> {
        val out = HashMap<String, MutableMap<String, ByteArray>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY, TEXT_VECTOR.VEC)
                .from(TEXT_VECTOR)
                .where(TEXT_VECTOR.ITEM_ID.`in`(chunk), TEXT_VECTOR.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    /** Every stored vector by [encoder] of the text keys [keys], streamed: (item, text key, vector). */
    fun forEach(
        encoder: String,
        keys: Collection<String>,
        action: (itemId: String, key: String, vector: FloatArray) -> Unit,
    ) = forEachHalves(encoder, keys) { id, key, bytes -> action(id, key, Vectors.fromHalf(bytes)) }

    /** [forEach] as stored: every vector its half-precision numbers, little-endian. */
    fun forEachHalves(
        encoder: String,
        keys: Collection<String>,
        action: (itemId: String, key: String, halves: ByteArray) -> Unit,
    ) {
        if (keys.isEmpty()) return
        val marks = keys.joinToString(",") { "?" }
        db.streamed(
            "SELECT item_id, text_key, vec FROM text_vector WHERE encoder = ? AND text_key IN ($marks)",
            listOf(encoder) + keys,
        ) { r ->
            action(r.getString(ITEM_COLUMN), r.getString(KEY_COLUMN), r.getBytes(VECTOR_COLUMN))
        }
    }

    fun count(encoder: String): Int = db.fetchCount(TEXT_VECTOR, TEXT_VECTOR.ENCODER.eq(encoder))

    companion object {
        /** The rows read or written by one statement: the length of a query's list, not a meaning. */
        internal const val BATCH = 1000

        /** The columns of the streamed query, by their place in it. */
        private const val ITEM_COLUMN = 1
        private const val KEY_COLUMN = 2
        private const val VECTOR_COLUMN = 3
    }
}
