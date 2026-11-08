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

    /** The stored vectors by [encoder] of the texts whose keys are given. */
    fun phrases(keys: Collection<Long>, encoder: String): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        keys.distinct().chunked(TextVectorRepository.BATCH).forEach { chunk ->
            db.select(PHRASE_VECTOR.TEXT_KEY, PHRASE_VECTOR.VEC)
                .from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.TEXT_KEY.`in`(chunk), PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetch { out[it.value1()!!] = Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** Which of the texts whose keys are given have a vector by [encoder] stored: their keys, the vectors not read. */
    fun kept(keys: Collection<Long>, encoder: String): Set<Long> {
        val out = HashSet<Long>()
        keys.distinct().chunked(TextVectorRepository.BATCH).forEach { chunk ->
            db.select(PHRASE_VECTOR.TEXT_KEY)
                .from(PHRASE_VECTOR)
                .where(PHRASE_VECTOR.TEXT_KEY.`in`(chunk), PHRASE_VECTOR.ENCODER.eq(encoder))
                .fetch { out += it.value1()!! }
        }
        return out
    }

    /** Stores vectors of short texts; one of another encoder under the same key is replaced. */
    fun savePhrases(rows: List<Pair<Long, FloatArray>>, encoder: String) {
        rows.chunked(TextVectorRepository.BATCH).forEach { chunk ->
            val merges =
                db.batch(
                    chunk.map { (key, v) ->
                        val packed = Vectors.half(v)
                        db.insertInto(PHRASE_VECTOR)
                            .set(PHRASE_VECTOR.TEXT_KEY, key)
                            .set(PHRASE_VECTOR.ENCODER, encoder)
                            .set(PHRASE_VECTOR.VEC, packed)
                            .onDuplicateKeyUpdate()
                            .set(PHRASE_VECTOR.ENCODER, encoder)
                            .set(PHRASE_VECTOR.VEC, packed)
                    }
                )
            // The same phrase met by two encodings at once races as a text's vector does
            writing.withLock { merges.execute() }
        }
    }
}
