package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.database.source.tables.references.PART_PARAGRAPH_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A paragraph of a part, by its number among the part's ([Paragraphs]), its vector and the hash of its text. */
class Paragraph(val partId: String, val number: Int, val vector: FloatArray, val hash: String)

/** The vector of every paragraph of every part of one source's works ([Paragraphs]), by an encoder. */
class ParagraphVectorRepository(private val db: DSLContext) {

    /** The parts whose paragraphs have no vectors by [encoder] yet, at most [limit]. */
    fun paragraphsToEncode(encoder: String, limit: Int): List<PartToEncode> =
        db.select(PART.ITEM_ID, PART.PART_ID, PART.CONTENT)
            .from(PART)
            .where(PART.CONTENT.isNotNull)
            .and(PART.PARAGRAPHS_ENCODER.isNull.or(PART.PARAGRAPHS_ENCODER.ne(encoder)))
            .limit(limit)
            .fetch { PartToEncode(it.value1()!!, it.value2()!!, it.value3()!!, Vectors.hash(it.value3()!!)) }

    fun countParagraphsToEncode(encoder: String): Int =
        db.fetchCount(
            PART,
            PART.CONTENT.isNotNull.and(PART.PARAGRAPHS_ENCODER.isNull.or(PART.PARAGRAPHS_ENCODER.ne(encoder))),
        )

    /**
     * Replaces the vectors of the paragraphs of a part ([Paragraphs]), made by [encoder] of the text of [hash], each
     * with the hash of its own text ([hashes]).
     */
    fun saveParagraphVectors(
        itemId: String,
        partId: String,
        encoder: String,
        hash: String,
        paragraphs: List<FloatArray?>,
        hashes: List<String>,
    ) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(PART_PARAGRAPH_VECTOR)
                .where(PART_PARAGRAPH_VECTOR.ITEM_ID.eq(itemId), PART_PARAGRAPH_VECTOR.PART_ID.eq(partId))
                .execute()
            paragraphs.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                tx.insertInto(PART_PARAGRAPH_VECTOR)
                    .set(PART_PARAGRAPH_VECTOR.ITEM_ID, itemId)
                    .set(PART_PARAGRAPH_VECTOR.PART_ID, partId)
                    .set(PART_PARAGRAPH_VECTOR.PARAGRAPH_NO, i)
                    .set(PART_PARAGRAPH_VECTOR.VEC, Vectors.half(v))
                    .set(PART_PARAGRAPH_VECTOR.TEXT_HASH, hashes[i])
                    .execute()
            }
            tx.update(PART)
                .set(PART.PARAGRAPHS_ENCODER, encoder)
                .set(PART.PARAGRAPHS_HASH, hash)
                .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
                .execute()
        }
    }

    /** Every paragraph's vector by [encoder] of the item's parts, in the parts' order. */
    fun paragraphVectors(itemId: String, encoder: String): List<Paragraph> =
        db.select(
                PART_PARAGRAPH_VECTOR.PART_ID,
                PART_PARAGRAPH_VECTOR.PARAGRAPH_NO,
                PART_PARAGRAPH_VECTOR.VEC,
                PART_PARAGRAPH_VECTOR.TEXT_HASH,
            )
            .from(PART_PARAGRAPH_VECTOR)
            .join(PART)
            .on(PART.ITEM_ID.eq(PART_PARAGRAPH_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_PARAGRAPH_VECTOR.PART_ID))
            .where(PART_PARAGRAPH_VECTOR.ITEM_ID.eq(itemId), PART.PARAGRAPHS_ENCODER.eq(encoder))
            .orderBy(PART.POSITION, PART_PARAGRAPH_VECTOR.PARAGRAPH_NO)
            .fetch { Paragraph(it.value1()!!, it.value2()!!, Vectors.fromHalf(it.value3()!!), it.value4()!!) }

    /** One paragraph by [encoder], its vector and hash; null when it has none. */
    fun paragraph(itemId: String, partId: String, number: Int, encoder: String): Paragraph? =
        db.select(PART_PARAGRAPH_VECTOR.VEC, PART_PARAGRAPH_VECTOR.TEXT_HASH)
            .from(PART_PARAGRAPH_VECTOR)
            .join(PART)
            .on(PART.ITEM_ID.eq(PART_PARAGRAPH_VECTOR.ITEM_ID), PART.PART_ID.eq(PART_PARAGRAPH_VECTOR.PART_ID))
            .where(
                PART_PARAGRAPH_VECTOR.ITEM_ID.eq(itemId),
                PART_PARAGRAPH_VECTOR.PART_ID.eq(partId),
                PART_PARAGRAPH_VECTOR.PARAGRAPH_NO.eq(number),
            )
            .and(PART.PARAGRAPHS_ENCODER.eq(encoder))
            .fetchOne { Paragraph(partId, number, Vectors.fromHalf(it.value1()!!), it.value2()!!) }

    /** Whether some part of the item has a text whose paragraphs have no vectors by [encoder] yet. */
    fun paragraphsPending(itemId: String, encoder: String): Boolean =
        db.fetchExists(
            PART,
            PART.ITEM_ID.eq(itemId),
            PART.CONTENT.isNotNull,
            PART.PARAGRAPHS_ENCODER.isNull.or(PART.PARAGRAPHS_ENCODER.ne(encoder)),
        )
}
