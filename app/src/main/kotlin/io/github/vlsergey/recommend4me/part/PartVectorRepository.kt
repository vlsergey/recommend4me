package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be encoded: its text and the hash it will be kept with. */
class PartToEncode(val itemId: String, val partId: String, val content: String, val hash: String) {
    /** Where the part stands in the order of the table's key: (item, part). */
    val place: Pair<String, String>
        get() = itemId to partId
}

/** The parts past [after] (item, part) in the order of the table's key; every part when null. */
internal fun pastPart(after: Pair<String, String>?): Condition =
    after?.let { (item, part) -> DSL.row(PART.ITEM_ID, PART.PART_ID).gt(item, part) } ?: DSL.noCondition()

/** The parts of the [items] — a portion's few works; every part when null. */
internal fun ofItems(items: Collection<String>?): Condition = items?.let { PART.ITEM_ID.`in`(it) } ?: DSL.noCondition()

/**
 * The parts with a text the [condition] chooses, at most [limit], in the order of the table's key, their texts read of
 * the [store]; a part whose text the store lost left out.
 */
internal fun partsToEncode(db: DSLContext, store: PartStore, condition: Condition, limit: Int): List<PartToEncode> =
    db.select(PART.ITEM_ID, PART.PART_ID, PART.CONTENT_HASH)
        .from(PART)
        .where(PART.CONTENT_HASH.isNotNull)
        .and(condition)
        .orderBy(PART.ITEM_ID, PART.PART_ID)
        .limit(limit)
        .fetch()
        .mapNotNull { r ->
            store.text(r.value1()!!, r.value2()!!)?.let { PartToEncode(r.value1()!!, r.value2()!!, it, r.value3()!!) }
        }

/** The rows streamed from the database at a time: memory, not meaning. */
private const val STREAMED = 5000

/**
 * The vector of every window of every part of one source's works, by an encoder: a long text is a set of vectors rather
 * than their average. The vectors are kept in the [store], outside the database; the database says by what encoder, of
 * what text, and how many ([PART.WINDOWS]).
 */
class PartVectorRepository(private val db: DSLContext, private val store: PartStore) {

    /**
     * The parts whose text has no vectors by [encoder] yet, at most [limit], in the order of the table's key from past
     * [after] (item, part; from the first when null): a pass goes on from the last it was given, not from the start of
     * the table again a batch.
     */
    fun toEncode(
        encoder: String,
        limit: Int,
        after: Pair<String, String>? = null,
        items: Collection<String>? = null,
    ): List<PartToEncode> =
        partsToEncode(
            db,
            store,
            PART.ENCODER.isNull.or(PART.ENCODER.ne(encoder)).and(pastPart(after)).and(ofItems(items)),
            limit,
        )

    /**
     * The items with a part whose text has no vectors by [windows] of its windows or by [paragraphs] of its paragraphs
     * yet: what a portion of the encoding chooses from ([PartVectors]).
     */
    fun itemsToEncode(windows: String, paragraphs: String): Set<String> =
        db.selectDistinct(PART.ITEM_ID)
            .from(PART)
            .where(PART.CONTENT_HASH.isNotNull)
            .and(
                PART.ENCODER.isNull
                    .or(PART.ENCODER.ne(windows))
                    .or(PART.PARAGRAPHS_ENCODER.isNull)
                    .or(PART.PARAGRAPHS_ENCODER.ne(paragraphs))
            )
            .fetchSize(STREAMED)
            .fetchSet { it.value1()!! }

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
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0))
            .groupBy(PART.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to it.value2()!!.toInt() }

    /** How many window vectors by [encoder] each of the items [ids] has, a batch a query: of a portion of a pass. */
    fun windowCountsOf(ids: Collection<String>, encoder: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, DSL.sum(PART.WINDOWS))
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0))
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
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0))
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
                .where(PART.ITEM_ID.`in`(chunk), PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0))
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
