package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.source.PartData
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

data class StoredPart(
    val partId: String,
    val position: Int,
    val title: String?,
    val hasText: Boolean,
    val publishedAt: Instant?,
)

/** A part whose text is to be counted. */
class PartToCount(val itemId: String, val partId: String, val content: String)

/**
 * The parts of the works' texts — chapters — of one source, their texts and their words counted; the vectors of their
 * windows are kept by [windows], those of their paragraphs by [paragraphs].
 */
class PartRepository(private val db: DSLContext) {

    /** The vector of every window of every part. */
    val windows = PartVectorRepository(db)

    /** The vector of every paragraph of every part. */
    val paragraphs = ParagraphVectorRepository(db)

    /**
     * Stores parts, a known one updated; a part named without its text keeps the text it had, and a part whose text
     * changed is encoded again.
     */
    fun save(itemId: String, parts: List<PartData>) {
        parts.forEach { p ->
            val id = p.id.take(ID_WIDTH)
            val known =
                db.select(PART.CONTENT).from(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).fetchOne()
            if (known == null) {
                db.insertInto(PART)
                    .set(PART.ITEM_ID, itemId)
                    .set(PART.PART_ID, id)
                    // A part whose place the page does not tell goes after the known ones
                    .set(PART.POSITION, if (p.position >= 0) p.position else UNKNOWN_POSITION)
                    .set(PART.TITLE, p.title?.take(TITLE_WIDTH))
                    .set(PART.CONTENT, p.content)
                    .set(PART.PUBLISHED_AT, p.publishedAt)
                    .execute()
                return@forEach
            }
            var update = db.update(PART).set(PART.ITEM_ID, itemId)
            if (p.position >= 0) update = update.set(PART.POSITION, p.position)
            p.title?.let { update = update.set(PART.TITLE, it.take(TITLE_WIDTH)) }
            if (p.publishedAt != null) update = update.set(PART.PUBLISHED_AT, p.publishedAt)
            if (p.content != null && p.content != known.value1()) {
                update =
                    update
                        .set(PART.CONTENT, p.content)
                        .setNull(PART.ENCODER)
                        .setNull(PART.ENCODED_HASH)
                        .setNull(PART.PARAGRAPHS_ENCODER)
                        .setNull(PART.PARAGRAPHS_HASH)
                        .setNull(PART.CHARS)
                        .setNull(PART.WORDS)
            }
            update.where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).execute()
        }
    }

    fun ofItem(itemId: String): List<StoredPart> =
        db.select(PART.PART_ID, PART.POSITION, PART.TITLE, PART.CONTENT.isNotNull, PART.PUBLISHED_AT)
            .from(PART)
            .where(PART.ITEM_ID.eq(itemId))
            .orderBy(PART.POSITION)
            .fetch { StoredPart(it.value1()!!, it.value2()!!, it.value3(), it.value4() == true, it.value5()) }

    fun text(itemId: String, partId: String): Pair<StoredPart, String>? =
        db.selectFrom(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).fetchOne()?.let {
            val content = it.content ?: return null
            StoredPart(it.partId!!, it.position!!, it.title, true, it.publishedAt) to content
        }

    /** The texts of every part of the item that has one, streamed in their order, each with its title. */
    fun forEachContent(itemId: String, action: (title: String?, content: String) -> Unit) {
        db.select(PART.TITLE, PART.CONTENT)
            .from(PART)
            .where(PART.ITEM_ID.eq(itemId), PART.CONTENT.isNotNull)
            .orderBy(PART.POSITION)
            .fetchSize(1)
            .fetchLazy()
            .use { cursor -> cursor.forEach { action(it.value1(), it.value2()!!) } }
    }

    /** The texts of every part of the items [ids] that has one, in their order: item to (part, title, text). */
    fun contentsOf(ids: Collection<String>): Map<String, List<Triple<String, String?, String>>> {
        val out = HashMap<String, MutableList<Triple<String, String?, String>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE, PART.CONTENT)
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.CONTENT.isNotNull)
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch {
                    out.getOrPut(it.value1()!!) { ArrayList() } += Triple(it.value2()!!, it.value3(), it.value4()!!)
                }
        }
        return out
    }

    /**
     * The titles of every part of the items [ids] that has a text, in their order — the texts not read: item to (part,
     * title).
     */
    fun titlesOf(ids: Collection<String>): Map<String, List<Pair<String, String?>>> {
        val out = HashMap<String, MutableList<Pair<String, String?>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE)
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.CONTENT.isNotNull)
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! to it.value3() }
        }
        return out
    }

    fun countWithText(): Int = db.fetchCount(PART, PART.CONTENT.isNotNull)

    /** The parts whose text is not counted yet, at most [limit]. */
    fun uncounted(limit: Int): List<PartToCount> =
        db.select(PART.ITEM_ID, PART.PART_ID, PART.CONTENT)
            .from(PART)
            .where(PART.CONTENT.isNotNull, PART.CHARS.isNull)
            .limit(limit)
            .fetch { PartToCount(it.value1()!!, it.value2()!!, it.value3()!!) }

    fun setCounted(itemId: String, partId: String, chars: Int, words: Int) {
        db.update(PART)
            .set(PART.CHARS, chars)
            .set(PART.WORDS, words)
            .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId))
            .execute()
    }

    /** The words and the characters of the texts kept, every part counted summed, of every item that has any. */
    fun counted(): Map<String, Pair<Double, Double>> =
        db.select(PART.ITEM_ID, DSL.sum(PART.WORDS), DSL.sum(PART.CHARS))
            .from(PART)
            .where(PART.CHARS.isNotNull)
            .groupBy(PART.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to (it.value2()!!.toDouble() to it.value3()!!.toDouble()) }

    private companion object {
        /** The items read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500

        /** The place of a part the page does not tell the place of: after every part known. */
        const val UNKNOWN_POSITION = 1_000_000

        /** The longest key of a part kept: the width of its column. */
        const val ID_WIDTH = 100

        /** The longest title of a part kept: the width of its column. */
        const val TITLE_WIDTH = 1000
    }
}
