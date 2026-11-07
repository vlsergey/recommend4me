package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.database.source.tables.references.PART_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be encoded: its text and the hash it will be kept with. */
class PartToEncode(val itemId: String, val partId: String, val content: String, val hash: String)

/**
 * The vector of every window of every part of one source's works, by an encoder: a long text is a set of vectors rather
 * than their average.
 */
class PartVectorRepository(private val db: DSLContext) {

    /** The parts whose text has no vectors by [encoder] yet, at most [limit]. */
    fun toEncode(encoder: String, limit: Int): List<PartToEncode> =
        db.select(PART.ITEM_ID, PART.PART_ID, PART.CONTENT)
            .from(PART)
            .where(PART.CONTENT.isNotNull)
            .and(PART.ENCODER.isNull.or(PART.ENCODER.ne(encoder)))
            .limit(limit)
            .fetch { PartToEncode(it.value1()!!, it.value2()!!, it.value3()!!, Vectors.hash(it.value3()!!)) }

    fun countToEncode(encoder: String): Int =
        db.fetchCount(PART, PART.CONTENT.isNotNull.and(PART.ENCODER.isNull.or(PART.ENCODER.ne(encoder))))

    /** Replaces the vectors of the windows of a part, made by [encoder] of the text of [hash]. */
    fun saveVectors(itemId: String, partId: String, encoder: String, hash: String, windows: List<FloatArray>) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(PART_VECTOR).where(PART_VECTOR.ITEM_ID.eq(itemId), PART_VECTOR.PART_ID.eq(partId)).execute()
            windows.forEachIndexed { i, v ->
                tx.insertInto(PART_VECTOR)
                    .set(PART_VECTOR.ITEM_ID, itemId)
                    .set(PART_VECTOR.PART_ID, partId)
                    .set(PART_VECTOR.WINDOW_NO, i)
                    .set(PART_VECTOR.VEC, Vectors.half(v))
                    .execute()
            }
            tx.update(PART)
                .set(PART.ENCODER, encoder)
                .set(PART.ENCODED_HASH, hash)
                .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
                .execute()
        }
    }

    /** How many window vectors by [encoder] every item has. */
    fun windowCounts(encoder: String): Map<String, Int> =
        db.select(PART_VECTOR.ITEM_ID, DSL.count())
            .from(PART_VECTOR)
            .join(PART)
            .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART.ENCODER.eq(encoder))
            .groupBy(PART_VECTOR.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    /** How many window vectors by [encoder] the item has. */
    fun windowCount(itemId: String, encoder: String): Int =
        db.fetchCount(
            db.select(PART_VECTOR.WINDOW_NO)
                .from(PART_VECTOR)
                .join(PART)
                .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.eq(itemId), PART.ENCODER.eq(encoder))
        )

    /** Every window vector by [encoder] of the items. */
    fun windows(itemIds: List<String>, encoder: String): Map<String, List<FloatArray>> {
        val out = HashMap<String, MutableList<FloatArray>>()
        itemIds.chunked(BATCH).forEach { ids ->
            db.select(PART_VECTOR.ITEM_ID, PART_VECTOR.VEC)
                .from(PART_VECTOR)
                .join(PART)
                .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.`in`(ids), PART.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** The window vectors by [encoder] of the parts of the items [ids]: item to part to its windows in order. */
    fun windowsByParts(ids: Collection<String>, encoder: String): Map<String, Map<String, List<FloatArray>>> {
        val out = HashMap<String, MutableMap<String, MutableList<FloatArray>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART_VECTOR.ITEM_ID, PART_VECTOR.PART_ID, PART_VECTOR.VEC)
                .from(PART_VECTOR)
                .join(PART)
                .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
                .where(PART_VECTOR.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder))
                .orderBy(PART_VECTOR.ITEM_ID, PART_VECTOR.PART_ID, PART_VECTOR.WINDOW_NO)
                .fetch {
                    out.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } +=
                        Vectors.fromHalf(it.value3()!!)
                }
        }
        return out
    }

    /** The window vectors by [encoder] of the item's parts: part to its windows in order. */
    fun windowsByPart(itemId: String, encoder: String): Map<String, List<FloatArray>> {
        val out = LinkedHashMap<String, MutableList<FloatArray>>()
        db.select(PART_VECTOR.PART_ID, PART_VECTOR.VEC)
            .from(PART_VECTOR)
            .join(PART)
            .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART_VECTOR.ITEM_ID.eq(itemId), PART.ENCODER.eq(encoder))
            .orderBy(PART.POSITION, PART_VECTOR.WINDOW_NO)
            .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        return out
    }

    /** [n] window vectors of the catalogue drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PART_VECTOR.VEC)
            .from(PART_VECTOR)
            .join(PART)
            .on(PART.ITEM_ID.eq(PART_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_VECTOR.PART_ID))
            .where(PART.ENCODER.eq(encoder))
            .orderBy(DSL.rand())
            .limit(n)
            .fetch { Vectors.fromHalf(it.value1()!!) }

    private companion object {
        /** The items read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
