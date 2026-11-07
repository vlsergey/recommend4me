package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.source.tables.references.PICTURE
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The pictures of one source waiting for the picture encoder: those to be worked on next, what the encoder made of
 * them, and the downloads that failed and when each is to be tried again.
 */
class PictureQueue(private val db: DSLContext, private val itemVectors: ItemPictureVectors) {

    /**
     * The pictures of the items [itemIds] to be worked on: without a vector of [encoder], or of an item in [keep] whose
     * original is not on disk; not failed, or failed and due to be tried again. In the order of [itemIds], every
     * picture of an item together.
     */
    fun pendingOf(itemIds: List<String>, encoder: String, keep: Set<String>, limit: Int): List<Picture> {
        if (itemIds.isEmpty()) return emptyList()
        val out = ArrayList<Picture>()
        itemIds.chunked(PICTURES_BATCH).forEach { chunk ->
            if (out.size >= limit) return@forEach
            val order = chunk.withIndex().associate { (i, id) -> id to i }
            val keepHere = chunk.filter { it in keep }
            out +=
                db.selectFrom(PICTURE)
                    .where(PICTURE.ITEM_ID.`in`(chunk), due())
                    .and(
                        analyzedBy(encoder)
                            .not()
                            .or(
                                if (keepHere.isEmpty()) DSL.falseCondition()
                                else PICTURE.ITEM_ID.`in`(keepHere).and(PICTURE.FULL_FILE.isNull)
                            )
                    )
                    .fetch { pictureOf(it, encoder) }
                    .sortedWith(compareBy({ order.getValue(it.itemId) }, { it.position }))
        }
        return out.take(limit)
    }

    /** The items with a picture not analysed by [encoder] yet (not failed, or due to be tried again). */
    fun itemsWithPending(encoder: String): Set<String> =
        db.selectDistinct(PICTURE.ITEM_ID).from(PICTURE).where(due(), analyzedBy(encoder).not()).fetchSet {
            it.value1()!!
        }

    /**
     * The picture analysed: its files on disk, its [vector] by [encoder] when it got one — the vectors of its item made
     * again — and whatever failed before past.
     */
    fun saveAnalysis(
        itemId: String,
        position: Int,
        vector: FloatArray?,
        encoder: String,
        previewFile: String?,
        fullFile: String?,
        now: Instant,
    ) {
        val update = db.update(PICTURE).set(PICTURE.PREVIEW_FILE, previewFile).set(PICTURE.FULL_FILE, fullFile)
        val withVector =
            if (vector != null) {
                update
                    .set(PICTURE.VEC, Vectors.half(vector))
                    .set(PICTURE.ENCODER, encoder)
                    .set(PICTURE.ANALYZED_AT, now)
            } else update
        // Downloaded: whatever failed before is past
        withVector
            .setNull(PICTURE.ERROR)
            .set(PICTURE.FAILURES, 0)
            .setNull(PICTURE.RETRY_AT)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
            .execute()
        if (vector != null) itemVectors.refreshItemVectors(listOf(itemId), encoder)
    }

    /**
     * The picture's download failed the [failures]-th time in a row: tried again at [retryAt], never when it is null —
     * a picture that cannot be decoded, or is gone (404).
     */
    fun saveError(itemId: String, position: Int, error: String, failures: Int, retryAt: Instant?) {
        db.update(PICTURE)
            .set(PICTURE.ERROR, error.take(ERROR_LENGTH))
            .set(PICTURE.FAILURES, failures)
            .set(PICTURE.RETRY_AT, retryAt)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
            .execute()
    }

    /** The earliest moment a failed picture is to be tried again; null when none is. */
    fun nextRetry(): Instant? =
        db.select(DSL.min(PICTURE.RETRY_AT)).from(PICTURE).where(PICTURE.ERROR.isNotNull).fetchOne()?.value1()

    private companion object {
        /** The longest error its column holds. */
        const val ERROR_LENGTH = 500

        /** Not failed, or failed and due to be tried again now. */
        fun due(): Condition = PICTURE.ERROR.isNull.or(PICTURE.RETRY_AT.le(Instant.now()))
    }
}
