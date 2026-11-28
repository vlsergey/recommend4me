package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.WholeReadRefused
import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import org.jooq.DSLContext

/**
 * THE FULL-TEXT VECTORS OF THE WINDOWS OF THE TEXTS OF ONE SOURCE'S ITEMS
 * ([io.github.vlsergey.recommend4me.fulltext.FullTextVectors]): of every chapter by its part's id, of every text
 * searched by meaning by [PieceRepository.textKey] of its key — kept in the store of the parts, read whole by a search;
 * the key of the centre they were made by kept with the chapter ([PART.FULL_TEXT]) and with the text's vector
 * ([TEXT_VECTOR.FULL_TEXT]).
 */
class FullTextVectorRepository(private val db: DSLContext, private val store: PartStore) {

    /** The full-text vectors of the chapter [partId] of the item, made by the centre [key]. */
    fun save(itemId: Long, partId: String, key: String, vectors: ByteArray) {
        store.fullText.put(itemId.toString(), partId, vectors)
        db.update(PART).set(PART.FULL_TEXT, key).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).execute()
    }

    /** The full-text vectors of the text field [field] of the item, made by the centre [key]. */
    fun saveText(itemId: Long, field: String, key: String, vectors: ByteArray) {
        store.fullText.put(itemId.toString(), PieceRepository.textKey(field), vectors)
        db.update(TEXT_VECTOR)
            .set(TEXT_VECTOR.FULL_TEXT, key)
            .where(TEXT_VECTOR.ITEM_ID.eq(itemId), TEXT_VECTOR.TEXT_KEY.eq(field))
            .execute()
    }

    /** The full-text vectors and the pieces of the text field [field] of the item gone with its vector. */
    fun forgetText(itemId: Long, field: String) {
        store.fullText.delete(itemId.toString(), PieceRepository.textKey(field))
        store.pieces.delete(itemId.toString(), PieceRepository.textKey(field))
    }

    /**
     * Every text's full-text vectors kept, in the order of the keys, as one pass of the store: its item, its text, its
     * full-text vectors.
     */
    fun forEach(action: (itemId: Long, text: String, vectors: ByteArray) -> Unit) =
        WholeReadRefused.checked("every full-text vector") {
            store.fullText.forEach { item, text, vectors -> action(item.toLong(), text, vectors) }
        }
}
