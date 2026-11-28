package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A part whose text is to be encoded: its text and the hash it will be kept with. */
class PartToEncode(val itemId: Long, val partId: String, val content: String, val hash: String)

/** A part of a work whose text waits to be encoded: its [windows], its [paragraphs], its [pieces], or several. */
class PartWaiting(
    val itemId: Long,
    val partId: String,
    val hash: String,
    val windows: Boolean,
    val paragraphs: Boolean,
    val pieces: Boolean = false,
    val fullText: Boolean = false,
) {
    /** Whether anything of the part waits. */
    val waits: Boolean
        get() = windows || paragraphs || pieces || fullText
}

/**
 * The vector of every window of every part of one source's works, by an encoder: a long text is a set of vectors rather
 * than their average. The vectors are kept in the [store], outside the database; the database says by what encoder, of
 * what text, and how many ([PART.WINDOWS]).
 */
class PartVectorRepository(private val db: DSLContext, private val store: PartStore) {

    /**
     * The parts of the works [items] whose text waits to be encoded — its windows by [windows], its paragraphs by
     * [paragraphs], its pieces by the projection [pieces] and its full-text vectors by the centre [fullText] when there
     * are any — in the order of the table's key: read work by work, by the key. A list of keys bound as parameters is
     * no index to H2, nor a bound of (item, part) past the last part given: it walked the parts of 1.4 million works to
     * the end of the table for every batch of eight (owner, 2026-11-24: a turn of 50 works took 47 s).
     */
    fun waitingOf(
        items: Collection<Long>,
        windows: String,
        paragraphs: String,
        pieces: String? = null,
        fullText: String? = null,
    ): List<PartWaiting> =
        items.distinct().sorted().flatMap { item ->
            db.select(
                    PART.PART_ID,
                    PART.CONTENT_HASH,
                    PART.ENCODER,
                    PART.PARAGRAPHS_ENCODER,
                    PART.PIECES,
                    PART.FULL_TEXT,
                )
                .from(PART)
                .where(PART.ITEM_ID.eq(item), PART.CONTENT_HASH.isNotNull, onSite)
                .orderBy(PART.PART_ID)
                .fetch()
                .mapNotNull { r ->
                    PartWaiting(
                            item,
                            r.value1()!!,
                            r.value2()!!,
                            windows = r.value3() != windows,
                            paragraphs = r.value4() != paragraphs,
                            pieces = pieces != null && r.value5() != pieces,
                            fullText = fullText != null && r.value6() != fullText,
                        )
                        .takeIf { it.waits }
                }
        }

    /** The vectors of the windows kept of the [parts] — item and part —, by part; a part of none left out. */
    fun windowsOfParts(parts: List<Pair<Long, String>>): Map<Pair<Long, String>, List<FloatArray>> =
        parts
            .groupBy({ it.first }, { it.second })
            .flatMap { (item, own) -> store.windows(item.toString(), own).map { (part, w) -> (item to part) to w } }
            .toMap()

    /** The texts of the [parts], of the store; a part whose text the store lost left out. */
    fun texts(parts: List<PartWaiting>): List<PartToEncode> = parts.mapNotNull { p ->
        store.text(p.itemId.toString(), p.partId)?.let { PartToEncode(p.itemId, p.partId, it, p.hash) }
    }

    /** Replaces the vectors of the windows of a part, made by [encoder] of the text of [hash]: kept, then told. */
    fun saveVectors(itemId: Long, partId: String, encoder: String, hash: String, windows: List<FloatArray>) {
        store.putWindows(itemId.toString(), partId, windows)
        db.update(PART)
            .set(PART.ENCODER, encoder)
            .set(PART.ENCODED_HASH, hash)
            .set(PART.WINDOWS, windows.size)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** How many window vectors by [encoder] the source's items have in all: counted by the database, no row read. */
    fun windowTotal(encoder: String): Long =
        db.select(DSL.sum(PART.WINDOWS))
            .from(PART)
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
            .fetchOne()
            ?.value1()
            ?.toLong() ?: 0L

    /** How many window vectors by [encoder] each of the items [ids] has, a batch a query: of a portion of a pass. */
    fun windowCountsOf(ids: Collection<Long>, encoder: String): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, DSL.sum(PART.WINDOWS))
                .from(PART)
                .where(
                    PART.ITEM_ID.`in`(chunk),
                    PART.ENCODER.eq(encoder),
                    PART.WINDOWS.gt(0),
                    onSite,
                )
                .groupBy(PART.ITEM_ID)
                .fetch { out[it.value1()!!] = it.value2()!!.toInt() }
        }
        return out
    }

    /** Every window vector by [encoder] of the items. */
    fun windows(itemIds: List<Long>, encoder: String): Map<Long, List<FloatArray>> {
        val out = HashMap<Long, List<FloatArray>>()
        encodedParts(itemIds, encoder).forEach { (item, parts) ->
            val windows = store.windows(item.toString(), parts)
            parts.flatMap { windows[it].orEmpty() }.takeIf { it.isNotEmpty() }?.let { out[item] = it }
        }
        return out
    }

    /** The window vectors by [encoder] of the item's parts: part to its windows in order. */
    fun windowsByPart(itemId: Long, encoder: String): Map<String, List<FloatArray>> {
        val parts = encodedParts(listOf(itemId), encoder)[itemId].orEmpty()
        val windows = store.windows(itemId.toString(), parts)
        val out = LinkedHashMap<String, List<FloatArray>>()
        parts.forEach { p -> windows[p]?.takeIf { it.isNotEmpty() }?.let { out[p] = it } }
        return out
    }

    /** The texts of [n] parts of the source drawn at random: what the projection of the pieces is learnt of. */
    fun sampleTexts(n: Int): List<String> =
        db.select(PART.ITEM_ID, PART.PART_ID)
            .from(PART)
            .where(PART.CONTENT_HASH.isNotNull, onSite)
            .orderBy(DSL.rand())
            .limit(n)
            .fetch()
            .mapNotNull { r -> store.text(r.value1()!!.toString(), r.value2()!!) }

    /** [n] window vectors of the catalogue drawn at random: a window of each of [n] parts drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PART.ITEM_ID, PART.PART_ID)
            .from(PART)
            .where(PART.ENCODER.eq(encoder), PART.WINDOWS.gt(0), onSite)
            .orderBy(DSL.rand())
            .limit(n)
            .fetch()
            .mapNotNull { r ->
                store.windows(r.value1()!!.toString(), listOf(r.value2()!!))[r.value2()!!]?.randomOrNull()
            }

    /** The parts of the items [ids] with windows by [encoder], in their order: item to its parts. */
    private fun encodedParts(ids: Collection<Long>, encoder: String): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID)
                .from(PART)
                .where(
                    PART.ITEM_ID.`in`(chunk),
                    PART.ENCODER.eq(encoder),
                    PART.WINDOWS.gt(0),
                    onSite,
                )
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
