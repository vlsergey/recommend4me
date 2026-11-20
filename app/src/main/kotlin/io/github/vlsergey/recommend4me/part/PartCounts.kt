package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be counted. */
class PartToCount(val itemId: String, val partId: String, val content: String)

/**
 * THE CHARACTERS AND THE WORDS OF THE PARTS' TEXTS OF ONE SOURCE, counted once a text is kept and kept with the part:
 * what a work's size is known by when its site tells none ([io.github.vlsergey.recommend4me.size.SizeExamples]). The
 * texts read of the [store].
 */
class PartCounts(private val db: DSLContext, private val store: PartStore) {

    /**
     * The parts whose text is kept and not counted yet, by their item and part — of the items [ids] alone, by their
     * keys, or of every item, in one pass of the table: a pass a hundred parts walked the table again and again
     * (2026-11-20).
     */
    fun uncounted(ids: Collection<String>?): List<Pair<String, String>> =
        (ids?.distinct()?.chunked(BATCH) ?: listOf(null)).flatMap { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID)
                .from(PART)
                .where(
                    PART.CONTENT_HASH.isNotNull,
                    PART.CHARS.isNull,
                    chunk?.let { PART.ITEM_ID.`in`(it) } ?: DSL.noCondition(),
                )
                .fetch { it.value1()!! to it.value2()!! }
        }

    /** The texts of the [parts] to count, by their item and part; one whose text the store lost left out. */
    fun toCount(parts: List<Pair<String, String>>): List<PartToCount> = parts.mapNotNull { (item, part) ->
        store.text(item, part)?.let { PartToCount(item, part, it) }
    }

    fun setCounted(itemId: String, partId: String, chars: Int, words: Int) {
        db.update(PART)
            .set(PART.CHARS, chars)
            .set(PART.WORDS, words)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** The words and the characters of the texts kept, every part counted summed, of every item that has any. */
    fun counted(): Map<String, Pair<Double, Double>> {
        // Streamed and summed here, no grouping asked of the database: H2's grouping of every counted part of ficbook
        // took 83 s of a search of duplicates (2026-11-20)
        val out = HashMap<String, DoubleArray>()
        db.select(PART.ITEM_ID, PART.WORDS, PART.CHARS)
            .from(PART)
            .where(PART.CHARS.isNotNull)
            .fetchSize(STREAMED)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    val sum = out.getOrPut(it.value1()!!) { DoubleArray(2) }
                    sum[0] += it.value2()?.toDouble() ?: 0.0
                    sum[1] += it.value3()!!.toDouble()
                }
            }
        return out.mapValues { (_, sum) -> sum[0] to sum[1] }
    }

    /** The same of the items [ids] alone, a thousand at a time. */
    fun counted(ids: Collection<String>): Map<String, Pair<Double, Double>> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk -> out.also { it += countedAmong(chunk) } }

    /** Of the items [chunk]. */
    private fun countedAmong(chunk: List<String>): Map<String, Pair<Double, Double>> =
        db.select(PART.ITEM_ID, DSL.sum(PART.WORDS), DSL.sum(PART.CHARS))
            .from(PART)
            .where(PART.CHARS.isNotNull, PART.ITEM_ID.`in`(chunk))
            .groupBy(PART.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to (it.value2()!!.toDouble() to it.value3()!!.toDouble()) }

    private companion object {
        const val BATCH = 1000

        /** The rows of the parts read at a time as they are streamed. */
        const val STREAMED = 10_000
    }
}
