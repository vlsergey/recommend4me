package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_MANNER
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext

/** What every item's kept manner was made of: by the words of [wordsId], of the texts [textHash] tells. */
data class MannerMade(val wordsId: Long, val textHash: String)

/**
 * The manners of one source's items ([MannerWords.of]), in its source database: every one with the words it was
 * measured by and the hash of the texts it was made of, made again when either changes. Kept in half precision: a
 * standardised share loses nothing that matters in its last digits.
 */
class MannerRepository(private val db: DSLContext) {

    /** What the manners of the items [ids] that have one were made of. */
    fun madeOf(ids: Collection<Long>): Map<Long, MannerMade> {
        val out = HashMap<Long, MannerMade>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_MANNER.ITEM_ID, ITEM_MANNER.WORDS_ID, ITEM_MANNER.TEXT_HASH)
                .from(ITEM_MANNER)
                .where(ITEM_MANNER.ITEM_ID.`in`(chunk))
                .fetch { out[it.value1()!!] = MannerMade(it.value2()!!, it.value3()!!) }
        }
        return out
    }

    fun save(itemId: Long, made: MannerMade, vector: FloatArray) {
        val packed = Vectors.half(vector)
        db.insertInto(ITEM_MANNER)
            .set(ITEM_MANNER.ITEM_ID, itemId)
            .set(ITEM_MANNER.WORDS_ID, made.wordsId)
            .set(ITEM_MANNER.TEXT_HASH, made.textHash)
            .set(ITEM_MANNER.VEC, packed)
            .onDuplicateKeyUpdate()
            .set(ITEM_MANNER.WORDS_ID, made.wordsId)
            .set(ITEM_MANNER.TEXT_HASH, made.textHash)
            .set(ITEM_MANNER.VEC, packed)
            .execute()
    }

    /** The manners of the items [ids] — of no text now, or of no word of the words — taken away. */
    fun remove(ids: Collection<Long>) {
        ids.chunked(BATCH).forEach { chunk ->
            db.deleteFrom(ITEM_MANNER).where(ITEM_MANNER.ITEM_ID.`in`(chunk)).execute()
        }
    }

    /** The manners of the items [ids] measured by the words [wordsId]: item to vector. */
    fun ofMany(ids: Collection<Long>, wordsId: Long): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM_MANNER.ITEM_ID, ITEM_MANNER.VEC)
                .from(ITEM_MANNER)
                .where(
                    ITEM_MANNER.ITEM_ID.`in`(chunk),
                    ITEM_MANNER.WORDS_ID.eq(wordsId),
                )
                .fetch { out[it.value1()!!] = Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    private companion object {
        /** The items of one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
