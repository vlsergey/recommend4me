package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE CHARACTERS AND THE WORDS OF THE KEPT TEXTS OF ONE SOURCE'S ITEMS, as they lie (owner, 2026-11-20: "such things
 * must just lie with the chapters, with no counting"): a part's counted as its text is written ([PartRepository.save]),
 * an item's every part's summed as its parts are written ([totalled]) — what a work's size is known by when its site
 * tells none ([io.github.vlsergey.recommend4me.size.SizeExamples]). Read, never counted.
 */
class PartCounts(private val db: DSLContext) {

    /** The words and the characters of every part of the item [itemId] that has a text counted, summed, kept. */
    fun totalled(itemId: String) {
        val sum =
            db.select(DSL.sum(PART.WORDS), DSL.sum(PART.CHARS))
                .from(PART)
                .where(PART.ITEM_ID.eq(itemId), PART.CHARS.isNotNull)
                .fetchOne()
        db.update(ITEM)
            .set(ITEM.TEXT_WORDS, sum?.value1()?.toInt())
            .set(ITEM.TEXT_CHARS, sum?.value2()?.toInt())
            .where(ITEM.ITEM_ID.eq(itemId))
            .execute()
    }

    /** The words and the characters of the texts of every item that has any, as kept. */
    fun counted(): Map<String, Pair<Double, Double>> {
        val out = HashMap<String, Pair<Double, Double>>()
        db.select(ITEM.ITEM_ID, ITEM.TEXT_WORDS, ITEM.TEXT_CHARS)
            .from(ITEM)
            .where(ITEM.TEXT_CHARS.isNotNull)
            .fetchSize(STREAMED)
            .fetchLazy()
            .use { cursor -> cursor.forEach { put(out, it.value1()!!, it.value2(), it.value3()!!) } }
        return out
    }

    /** The same of the items [ids] alone, by the table's key, a thousand at a time. */
    fun counted(ids: Collection<String>): Map<String, Pair<Double, Double>> {
        val out = HashMap<String, Pair<Double, Double>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(ITEM.ITEM_ID, ITEM.TEXT_WORDS, ITEM.TEXT_CHARS)
                .from(ITEM)
                .where(ITEM.ITEM_ID.`in`(chunk), ITEM.TEXT_CHARS.isNotNull)
                .fetch { put(out, it.value1()!!, it.value2(), it.value3()!!) }
        }
        return out
    }

    private fun put(out: MutableMap<String, Pair<Double, Double>>, id: String, words: Int?, chars: Int) {
        out[id] = (words ?: 0).toDouble() to chars.toDouble()
    }

    companion object {
        /**
         * The ids the index of the list keeps an item's words and characters of its kept texts by, among its numbers:
         * of every source alike.
         */
        const val WORDS_ID = "text.words"
        const val CHARS_ID = "text.chars"

        private const val BATCH = 1000

        /** The rows read at a time as they are streamed. */
        private const val STREAMED = 10_000
    }
}
