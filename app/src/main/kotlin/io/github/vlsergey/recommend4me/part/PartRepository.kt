package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

data class StoredPart(
    val partId: String,
    val position: Int,
    val title: String?,
    val hasText: Boolean,
    val publishedAt: Instant?,
    /** When a whole table of contents of its site no longer named it; null — it is on the site. */
    val takenOffAt: Instant? = null,
)

/**
 * The parts on their site — none a whole table of contents read last left out ([PartRepository.save]): what a work is
 * made of, its words, its vectors, its search. A part taken off keeps its text, shown on the screen of the work alone.
 */
internal val onSite: Condition
    get() = PART.TAKEN_OFF_AT.isNull

/**
 * The parts of the works' texts — chapters — of one source, their texts and their words counted; the vectors of their
 * windows are kept by [windows], those of their paragraphs by [paragraphs]. THE TEXTS AND THE VECTORS ARE KEPT OUTSIDE
 * THE DATABASE, in the [store] (user, 2026-11-17): the database says what a part has — a part has a text when the hash
 * of one is written ([PART.CONTENT_HASH]) — the store holds the text.
 */
class PartRepository(private val db: DSLContext, private val store: PartStore) {

    /** The vector of every window of every part. */
    val windows = PartVectorRepository(db, store)

    /** The vector of every paragraph of every part. */
    val paragraphs = ParagraphVectorRepository(db, store)

    /** The codes of the pieces of the chapters and of the text fields. */
    val pieces = PieceRepository(db, store)
    val fullText = FullTextVectorRepository(db, store)

    /** What every part is made of, its text not read. */
    val marks = PartMarks(db)

    /** The texts of the parts of every item, an item at a time. */
    val texts = ItemTexts(db, store)

    /** The characters and the words of the items' texts, as they lie. */
    val counts = PartCounts(db, store)

    /**
     * Stores parts, a known one updated; a part named without its text keeps the text it had, and a part whose text
     * changed is encoded again. The text is kept in the store before its hash says the part has it. A part named is on
     * the site; of the [whole] table of contents, a part kept and not named is taken off it ([StoredPart.takenOffAt]).
     * Whether some part was taken off or came back: what the work is made of changed.
     */
    fun save(itemId: String, parts: List<PartData>, whole: Boolean = false, now: Instant = Instant.now()): Boolean {
        parts.forEach { p ->
            val id = p.id.take(ID_WIDTH)
            val known =
                db.select(PART.CONTENT_HASH).from(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).fetchOne()
            val content = p.content
            val hash = content?.let(Vectors::hash)
            // A text's characters and words counted as it is written, kept with its part
            val words = content?.let(WordCount::of)
            val changed = hash != null && hash != known?.value1()
            if (content != null && changed) store.putText(itemId, id, content)
            if (known == null) {
                db.insertInto(PART)
                    .set(PART.ITEM_ID, itemId)
                    .set(PART.PART_ID, id)
                    // A part whose place the page does not tell goes after the known ones
                    .set(PART.POSITION, if (p.position >= 0) p.position else UNKNOWN_POSITION)
                    .set(PART.TITLE, p.title?.take(TITLE_WIDTH))
                    .set(PART.CONTENT_HASH, hash)
                    .set(PART.PUBLISHED_AT, p.publishedAt)
                    .set(PART.CHARS, content?.length)
                    .set(PART.WORDS, words)
                    .execute()
                return@forEach
            }
            var update = db.update(PART).set(PART.ITEM_ID, itemId)
            if (p.position >= 0) update = update.set(PART.POSITION, p.position)
            p.title?.let { update = update.set(PART.TITLE, it.take(TITLE_WIDTH)) }
            if (p.publishedAt != null) update = update.set(PART.PUBLISHED_AT, p.publishedAt)
            if (changed) {
                update =
                    update
                        .set(PART.CONTENT_HASH, hash)
                        .setNull(PART.ENCODER)
                        .setNull(PART.ENCODED_HASH)
                        .setNull(PART.WINDOWS)
                        .setNull(PART.PARAGRAPHS_ENCODER)
                        .setNull(PART.PARAGRAPHS_HASH)
                        .setNull(PART.PIECES)
                        .setNull(PART.FULL_TEXT)
                        .set(PART.CHARS, content?.length)
                        .set(PART.WORDS, words)
            }
            update.where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(id)).execute()
        }
        val named = parts.map { it.id.take(ID_WIDTH) }.distinct()
        val back =
            db.update(PART)
                .setNull(PART.TAKEN_OFF_AT)
                .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.`in`(named), PART.TAKEN_OFF_AT.isNotNull)
                .execute()
        val off =
            if (!whole || named.isEmpty()) 0
            else
                db.update(PART)
                    .set(PART.TAKEN_OFF_AT, now)
                    .where(PART.ITEM_ID.eq(itemId), PART.PART_ID.notIn(named), onSite)
                    .execute()
        counts.totalled(itemId)
        return back + off > 0
    }

    /** Every part of the item, those taken off the site too, in their order. */
    fun ofItem(itemId: String): List<StoredPart> =
        db.select(
                PART.PART_ID,
                PART.POSITION,
                PART.TITLE,
                PART.CONTENT_HASH.isNotNull,
                PART.PUBLISHED_AT,
                PART.TAKEN_OFF_AT,
            )
            .from(PART)
            .where(PART.ITEM_ID.eq(itemId))
            .orderBy(PART.POSITION)
            .fetch {
                StoredPart(it.value1()!!, it.value2()!!, it.value3(), it.value4() == true, it.value5(), it.value6())
            }

    fun text(itemId: String, partId: String): Pair<StoredPart, String>? =
        db.selectFrom(PART).where(PART.ITEM_ID.eq(itemId), PART.PART_ID.eq(partId)).fetchOne()?.let {
            if (it.contentHash == null) return null
            val content = store.text(itemId, partId) ?: return null
            StoredPart(it.partId!!, it.position!!, it.title, true, it.publishedAt, it.takenOffAt) to content
        }

    /** The texts of the parts [partIds] of the item, by part, read of the store alone; a part of none left out. */
    fun textsOf(itemId: String, partIds: Collection<String>): Map<String, String> = store.texts(itemId, partIds)

    /** The texts of the [parts] of many items — item and part —, read of the store at once ([PartStore.texts]). */
    fun textsOf(parts: Collection<Pair<String, String>>): Map<Pair<String, String>, String> = store.texts(parts)

    /**
     * The texts of every part of the item that has one, in their order, each with its title: one text read at a time.
     */
    fun forEachContent(itemId: String, action: (title: String?, content: String) -> Unit) {
        titlesOf(listOf(itemId))[itemId].orEmpty().forEach { (part, title) ->
            store.text(itemId, part)?.let { action(title, it) }
        }
    }

    /** The texts of every part of the items [ids] that has one, in their order: item to (part, title, text). */
    fun contentsOf(ids: Collection<String>): Map<String, List<Triple<String, String?, String>>> =
        titlesOf(ids)
            .mapValues { (item, parts) ->
                val texts = store.texts(item, parts.map { it.first })
                parts.mapNotNull { (part, title) -> texts[part]?.let { Triple(part, title, it) } }
            }
            .filterValues { it.isNotEmpty() }

    /**
     * The titles of every part of the items [ids] on the site that has a text, in their order — the texts not read:
     * item to (part, title).
     */
    fun titlesOf(ids: Collection<String>): Map<String, List<Pair<String, String?>>> {
        val out = HashMap<String, MutableList<Pair<String, String?>>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE)
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.CONTENT_HASH.isNotNull, onSite)
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! to it.value3() }
        }
        return out
    }

    /**
     * When the first part of each of the items [ids] was published, as its site says: the earliest date of its parts;
     * an item none of whose parts is dated has none.
     */
    fun firstPublished(ids: Collection<String>): Map<String, Instant> {
        val out = HashMap<String, Instant>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, DSL.min(PART.PUBLISHED_AT))
                .from(PART)
                .where(PART.ITEM_ID.`in`(chunk), PART.PUBLISHED_AT.isNotNull)
                .groupBy(PART.ITEM_ID)
                .fetch { r -> r.value2()?.let { out[r.value1()!!] = it } }
        }
        return out
    }

    /** Everything of the item's parts the store keeps gone, as the work is removed: its rows go with the item's. */
    fun forget(itemId: String) = store.forget(itemId)

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
