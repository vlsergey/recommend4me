package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be encoded: its text and the hash it will be kept with. */
class PartToEncode(val itemId: String, val partId: String, val content: String, val hash: String)

/** A part of a work whose text waits to be encoded: its [windows], its [paragraphs], or both. */
class PartWaiting(
    val itemId: String,
    val partId: String,
    val hash: String,
    val windows: Boolean,
    val paragraphs: Boolean,
)

/**
 * The vector of every window of every part of one source's works, by an encoder: a long text is a set of vectors rather
 * than their average. The vectors are kept in the [store], outside the database; the database says by what encoder, of
 * what text, and how many ([PART.WINDOWS]).
 */
class PartVectorRepository(private val db: DSLContext, private val store: PartStore) {

    /**
     * The parts of the works [items] whose text waits to be encoded — its windows by [windows], its paragraphs by
     * [paragraphs] — in the order of the table's key: read work by work, by the key. A list of keys bound as parameters
     * is no index to H2, nor a bound of (item, part) past the last part given: it walked the parts of 1.4 million works
     * to the end of the table for every batch of eight (owner, 2026-11-24: a turn of 50 works took 47 s).
     */
    fun waitingOf(items: Collection<String>, windows: String, paragraphs: String): List<PartWaiting> =
        items.distinct().sorted().flatMap { item ->
            db.select(PART.PART_ID, PART.CONTENT_HASH, PART.ENCODER, PART.PARAGRAPHS_ENCODER)
                .from(PART)
                .where(PART.ITEM_ID.eq(item), PART.CONTENT_HASH.isNotNull, onSite)
                .orderBy(PART.PART_ID)
                .fetch()
                .mapNotNull { r ->
                    val ofWindows = r.value3() != windows
                    val ofParagraphs = r.value4() != paragraphs
                    if (ofWindows || ofParagraphs)
                        PartWaiting(item, r.value1()!!, r.value2()!!, ofWindows, ofParagraphs)
                    else null
                }
        }

    /** The texts of the [parts], of the store; a part whose text the store lost left out. */
    fun texts(parts: List<PartWaiting>): List<PartToEncode> = parts.mapNotNull { p ->
        store.text(p.itemId, p.partId)?.let { PartToEncode(p.itemId, p.partId, it, p.hash) }
    }

    /** Replaces the vectors of the windows of a part, made by [encoder] of the text of [hash]: kept, then told. */
    fun saveVectors(itemId: String, partId: String, encoder: String, hash: String, windows: List<FloatArray>) {
        store.putWindows(itemId, partId, windows)
        db.update(PART)
            .set(PART.ENCODER, encoder)
            .set(PART.ENCODED_HASH, hash)
            .set(PART.WINDOWS, windows.size)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** How many window vectors by [encoder] every item has. */
    fun windowCounts(encoder: String): Map<String, Int> =
        db.select(PART.ITEM_ID, DSL.sum(PART.WINDOWS))
            .from(PART)
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
            .groupBy(PART.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to it.value2()!!.toInt() }

    /** How many window vectors by [encoder] each of the items [ids] has, a batch a query: of a portion of a pass. */
    fun windowCountsOf(ids: Collection<String>, encoder: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, DSL.sum(PART.WINDOWS))
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
                .groupBy(PART.ITEM_ID)
                .fetch { out[it.value1()!!] = it.value2()!!.toInt() }
        }
        return out
    }

    /** Every window vector by [encoder] of the items. */
    fun windows(itemIds: List<String>, encoder: String): Map<String, List<FloatArray>> =
        windowsByParts(itemIds, encoder).mapValues { (_, parts) -> parts.values.flatten() }

    /** The window vectors by [encoder] of the parts of the items [ids]: item to part to its windows in order. */
    fun windowsByParts(ids: Collection<String>, encoder: String): Map<String, Map<String, List<FloatArray>>> {
        val out = HashMap<String, Map<String, List<FloatArray>>>()
        encodedParts(ids, encoder).forEach { (item, parts) ->
            val windows = store.windows(item, parts)
            out[item] = parts.mapNotNull { p -> windows[p]?.takeIf { it.isNotEmpty() }?.let { p to it } }.toMap()
        }
        return out.filterValues { it.isNotEmpty() }
    }

    /** The window vectors by [encoder] of the item's parts: part to its windows in order. */
    fun windowsByPart(itemId: String, encoder: String): Map<String, List<FloatArray>> {
        val parts = encodedParts(listOf(itemId), encoder)[itemId].orEmpty()
        val windows = store.windows(itemId, parts)
        val out = LinkedHashMap<String, List<FloatArray>>()
        parts.forEach { p -> windows[p]?.takeIf { it.isNotEmpty() }?.let { out[p] = it } }
        return out
    }

    /** [n] window vectors of the catalogue drawn at random: a window of each of [n] parts drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PART.ITEM_ID, PART.PART_ID)
            .from(PART)
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
            .orderBy(DSL.rand())
            .limit(n)
            .fetch()
            .mapNotNull { r -> store.windows(r.value1()!!, listOf(r.value2()!!))[r.value2()!!]?.randomOrNull() }

    /** The parts of the items [ids] with windows by [encoder], in their order: item to its parts. */
    private fun encodedParts(ids: Collection<String>, encoder: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID)
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! }
        }
        return out
    }

    private companion object {
        /** The items read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
