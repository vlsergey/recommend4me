package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext

/** A paragraph of a part, by its number among the part's ([Paragraphs]), its vector and the hash of its text. */
class Paragraph(val partId: String, val number: Int, val vector: FloatArray, val hash: String)

/**
 * The vector of every paragraph of every part of one source's works ([Paragraphs]), by an encoder: kept in the [store],
 * outside the database, each with the hash of its text; the database says by what encoder and of what text.
 */
class ParagraphVectorRepository(private val db: DSLContext, private val store: PartStore) {

    /**
     * Replaces the vectors of the paragraphs of a part ([Paragraphs]), made by [encoder] of the text of [hash], each
     * with the hash of its own text ([hashes]): kept, then told.
     */
    fun saveParagraphVectors(
        itemId: String,
        partId: String,
        encoder: String,
        hash: String,
        paragraphs: List<FloatArray?>,
        hashes: List<String>,
    ) {
        store.putParagraphs(itemId, partId, paragraphs, hashes)
        db.update(PART)
            .set(PART.PARAGRAPHS_ENCODER, encoder)
            .set(PART.PARAGRAPHS_HASH, hash)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** Every paragraph's vector by [encoder] of the item's parts, in the parts' order. */
    fun paragraphVectors(itemId: String, encoder: String): List<Paragraph> {
        val parts =
            db.select(PART.PART_ID)
                .from(PART)
                .where(PART.ITEM_ID.eq(itemId), PART.PARAGRAPHS_ENCODER.eq(encoder), onSite)
                .orderBy(PART.POSITION)
                .fetch { it.value1()!! }
        val kept = store.paragraphs(itemId, parts)
        return parts.flatMap { part ->
            kept[part].orEmpty().mapIndexedNotNull { i, p -> p?.let { Paragraph(part, i, it.vector, it.hash) } }
        }
    }

    /** One paragraph by [encoder], its vector and hash; null when it has none. */
    fun paragraph(itemId: String, partId: String, number: Int, encoder: String): Paragraph? {
        val encoded =
            db.fetchExists(
                PART,
                PART.ITEM_ID.eq(itemId),
                PART.PART_ID.eq(partId),
                PART.PARAGRAPHS_ENCODER.eq(encoder),
                onSite,
            )
        if (!encoded) return null
        return store.paragraphs(itemId, listOf(partId))[partId]?.getOrNull(number)?.let {
            Paragraph(partId, number, it.vector, it.hash)
        }
    }

    /** Whether some part of the item has a text whose paragraphs have no vectors by [encoder] yet. */
    fun paragraphsPending(itemId: String, encoder: String): Boolean =
        db.fetchExists(
            PART,
            PART.ITEM_ID.eq(itemId),
            PART.CONTENT_HASH.isNotNull,
            PART.PARAGRAPHS_ENCODER.isNull.or(PART.PARAGRAPHS_ENCODER.ne(encoder)),
            onSite,
        )
}
