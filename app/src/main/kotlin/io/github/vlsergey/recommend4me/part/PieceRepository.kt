package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import org.jooq.DSLContext

/**
 * THE CODES OF THE PIECES OF THE TEXTS OF ONE SOURCE'S ITEMS ([io.github.vlsergey.recommend4me.piece.PieceCodebook]):
 * of every chapter by its part's id, of every text field by [textKey] of its key — kept in the store of the parts; the
 * key of the projection they were made by kept with the chapter ([PART.PIECES]) and with the field's vector
 * ([TEXT_VECTOR.PIECES]).
 */
class PieceRepository(private val db: DSLContext, private val store: PartStore) {

    /** The codes of the chapter [partId] of the item, made by the projection [key]. */
    fun save(itemId: Long, partId: String, key: String, codes: ByteArray) {
        store.pieces.put(itemId.toString(), partId, codes)
        db.update(PART).set(PART.PIECES, key).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).execute()
    }

    /** The codes of the text field [field] of the item, made by the projection [key]. */
    fun saveText(itemId: Long, field: String, key: String, codes: ByteArray) {
        store.pieces.put(itemId.toString(), textKey(field), codes)
        db.update(TEXT_VECTOR)
            .set(TEXT_VECTOR.PIECES, key)
            .where(TEXT_VECTOR.ITEM_ID.eq(itemId), TEXT_VECTOR.TEXT_KEY.eq(field))
            .execute()
    }

    /** The codes of the texts [texts] of the item — parts' ids or [textKey]s —, by text; a text of none left out. */
    fun of(itemId: Long, texts: Collection<String>): Map<String, ByteArray> = store.pieces.of(itemId.toString(), texts)

    companion object {
        /** A text field's key among the parts' ids of its item: no part's id begins so. */
        fun textKey(field: String) = "text:$field"
    }
}
