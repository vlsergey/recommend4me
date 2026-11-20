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
class PartCounts(private val db: DSLContext, private val store: PartStore) {

    /** The words and the characters of every part of the item [itemId] that has a text counted, summed, kept. */
    fun totalled(itemId: String) {
        val sum =
            db.select(DSL.sum(PART.WORDS), DSL.sum(PART.CHARS))
                .from(PART)
                .where(PART.ITEM_ID.eq(itemId), PART.CHARS.isNotNull)
                .fetchOne()
        // The classes of the characters of every text of the item's, read of the store: an item's few
        val parts =
            db.select(PART.PART_ID)
                .from(PART)
                .where(PART.ITEM_ID.eq(itemId), PART.CONTENT_HASH.isNotNull)
                .fetch(PART.PART_ID)
        val classes = IntArray(CharClasses.NAMES.size)
        store.texts(itemId, parts.filterNotNull()).values.forEach { text ->
            CharClasses.of(text).forEachIndexed { k, v -> classes[k] += v }
        }
        val some = parts.isNotEmpty()
        var update =
            db.update(ITEM).set(ITEM.TEXT_WORDS, sum?.value1()?.toInt()).set(ITEM.TEXT_CHARS, sum?.value2()?.toInt())
        COLUMNS.forEachIndexed { k, column -> update = update.set(column, if (some) classes[k] else null) }
        update.where(ITEM.ITEM_ID.eq(itemId)).execute()
    }

    /**
     * The classes of the characters of the texts of the items [ids] — of every item when null — that have any
     * ([CharClasses]).
     */
    fun classes(ids: Collection<String>?): Map<String, IntArray> {
        val out = HashMap<String, IntArray>()
        fun read(condition: org.jooq.Condition) =
            db.select(ITEM.ITEM_ID, *COLUMNS.toTypedArray())
                .from(ITEM)
                .where(condition, ITEM.TEXT_LETTERS_1.isNotNull)
                .fetchSize(STREAMED)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { r ->
                        out[r.get(ITEM.ITEM_ID)!!] = IntArray(COLUMNS.size) { k -> r.get(COLUMNS[k]) ?: 0 }
                    }
                }
        if (ids == null) read(DSL.noCondition())
        else ids.distinct().chunked(BATCH).forEach { read(ITEM.ITEM_ID.`in`(it)) }
        return out
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

        /** The id the index keeps the count of the class [k] of an item's characters by ([CharClasses.NAMES]). */
        fun classId(k: Int) = "text." + CharClasses.NAMES[k]

        /** The item's columns of the classes, in the order of [CharClasses.NAMES]. */
        private val COLUMNS =
            listOf(
                ITEM.TEXT_LETTERS_1,
                ITEM.TEXT_LETTERS_2,
                ITEM.TEXT_LETTERS_3,
                ITEM.TEXT_PUNCTUATION_1,
                ITEM.TEXT_PUNCTUATION_2,
                ITEM.TEXT_SPACES,
                ITEM.TEXT_BREAKS,
                ITEM.TEXT_OTHER,
            )

        private const val BATCH = 1000

        /** The rows read at a time as they are streamed. */
        private const val STREAMED = 10_000
    }
}
