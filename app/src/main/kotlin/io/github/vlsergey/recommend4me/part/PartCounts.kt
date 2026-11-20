package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be counted. */
class PartToCount(val itemId: String, val partId: String, val content: String)

/**
 * THE CHARACTERS AND THE WORDS OF THE PARTS' TEXTS OF ONE SOURCE, counted once a text is kept and kept with the part:
 * what a work's size is known by when its site tells none ([io.github.vlsergey.recommend4me.size.KnownSizes]). The
 * texts read of the [store].
 */
class PartCounts(private val db: DSLContext, private val store: PartStore) {

    /** The parts whose text is not counted yet, at most [limit]; one whose text the store lost left out. */
    fun uncounted(limit: Int): List<PartToCount> =
        db.select(PART.ITEM_ID, PART.PART_ID)
            .from(PART)
            .where(PART.CONTENT_HASH.isNotNull, PART.CHARS.isNull)
            .limit(limit)
            .fetch { it.value1()!! to it.value2()!! }
            .mapNotNull { (item, part) -> store.text(item, part)?.let { PartToCount(item, part, it) } }

    fun setCounted(itemId: String, partId: String, chars: Int, words: Int) {
        db.update(PART)
            .set(PART.CHARS, chars)
            .set(PART.WORDS, words)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** The words and the characters of the texts kept, every part counted summed, of every item that has any. */
    fun counted(): Map<String, Pair<Double, Double>> = countedAmong(null)

    /** The same of the items [ids] alone, a thousand at a time. */
    fun counted(ids: Collection<String>): Map<String, Pair<Double, Double>> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk -> out.also { it += countedAmong(chunk) } }

    /** Of the items [chunk]; of every item when null. */
    private fun countedAmong(chunk: List<String>?): Map<String, Pair<Double, Double>> =
        db.select(PART.ITEM_ID, DSL.sum(PART.WORDS), DSL.sum(PART.CHARS))
            .from(PART)
            .where(PART.CHARS.isNotNull, chunk?.let { PART.ITEM_ID.`in`(it) } ?: DSL.noCondition())
            .groupBy(PART.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to (it.value2()!!.toDouble() to it.value3()!!.toDouble()) }

    private companion object {
        const val BATCH = 1000
    }
}
