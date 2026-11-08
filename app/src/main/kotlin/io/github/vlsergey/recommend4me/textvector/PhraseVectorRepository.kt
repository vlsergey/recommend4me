package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.source.tables.references.PHRASE_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jooq.DSLContext

/**
 * The vectors of short texts of one source — reviews, phrases — by the key of the text, so a text is encoded once.
 * Written under the lock of the texts' vectors ([writing]): the same phrase met by two encodings at once races as a
 * text's vector does.
 */
class PhraseVectorRepository(private val db: DSLContext, private val writing: ReentrantLock) {

    /**
     * The stored vectors by [encoder] of the texts whose keys are given. Of many, the encoder's whole in one streamed
     * pass, those asked for kept: a thousand keys at a time each read the table through in H2.
     */
    fun phrases(keys: Collection<Long>, encoder: String): Map<Long, FloatArray> {
        val asked = keys.toHashSet()
        val out = HashMap<Long, FloatArray>()
        if (asked.size <= TextVectorRepository.BATCH)
            db.select(PHRASE_VECTOR.TEXT_KEY, PHRASE_VECTOR.VEC)
                .from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.TEXT_KEY.`in`(asked), PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetch { out[it.value1()!!] = Vectors.fromHalf(it.value2()!!) }
        else
            db.select(PHRASE_VECTOR.TEXT_KEY, PHRASE_VECTOR.VEC)
                .from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetchSize(TextVectorRepository.BATCH)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { r ->
                        val key = r.value1()!!
                        if (key in asked) out[key] = Vectors.fromHalf(r.value2()!!)
                    }
                }
        return out
    }

    /**
     * Which of the texts whose keys are given have a vector by [encoder] stored: their keys, the vectors not read. Of
     * many, every key of the encoder in one pass.
     */
    fun kept(keys: Collection<Long>, encoder: String): Set<Long> {
        val asked = keys.toHashSet()
        val out = HashSet<Long>()
        if (asked.size <= TextVectorRepository.BATCH)
            db.select(PHRASE_VECTOR.TEXT_KEY)
                .from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.TEXT_KEY.`in`(asked), PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetch { out += it.value1()!! }
        else
            db.select(PHRASE_VECTOR.TEXT_KEY).from(PHRASE_VECTOR).where(PHRASE_VECTOR.ENCODER.eq(encoder)).fetch {
                val key = it.value1()!!
                if (key in asked) out += key
            }
        return out
    }

    /** Stores vectors of short texts; one of another encoder under the same key is replaced. */
    fun savePhrases(rows: List<Pair<Long, FloatArray>>, encoder: String) {
        rows.chunked(TextVectorRepository.BATCH).forEach { chunk ->
            // One statement prepared and bound a row each, not each row's statement — its vector inlined — parsed apart
            val merges =
                db.batch(
                    db.mergeInto(PHRASE_VECTOR, PHRASE_VECTOR.TEXT_KEY, PHRASE_VECTOR.ENCODER, PHRASE_VECTOR.VEC)
                        .key(PHRASE_VECTOR.TEXT_KEY)
                        .values(null as Long?, null as String?, null as ByteArray?)
                )
            chunk.forEach { (key, v) -> merges.bind(key, encoder, Vectors.half(v)) }
            // The same phrase met by two encodings at once races as a text's vector does
            writing.withLock { merges.execute() }
        }
    }
}
