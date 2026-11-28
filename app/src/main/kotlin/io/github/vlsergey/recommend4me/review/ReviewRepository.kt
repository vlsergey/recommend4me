package io.github.vlsergey.recommend4me.review

import io.github.vlsergey.recommend4me.database.source.tables.references.PHRASE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.REVIEW
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

data class StoredReview(
    val itemId: Long,
    val reviewId: String,
    val author: String?,
    val stars: Double?,
    val content: String,
    val postedAt: Instant?,
)

/** The opinions of other people about the items of one source: reviews, comments. */
class ReviewRepository(private val db: DSLContext) {

    /** Stores the reviews, a known one updated (its author may have edited it). */
    /** The [reviews] of the item kept, a review of its id written anew; whether any was new or other than kept. */
    fun save(itemId: Long, reviews: List<ReviewData>): Boolean {
        val kept = ofItem(itemId).associateBy { it.reviewId }
        val other = reviews.any { r ->
            kept[r.id.take(ID_LENGTH)].let { it == null || it.content != r.content || it.stars != r.stars }
        }
        reviews.forEach { r ->
            val key = textKeyOf(r.content)
            db.insertInto(REVIEW)
                .set(REVIEW.ITEM_ID, itemId)
                .set(REVIEW.REVIEW_ID, r.id.take(ID_LENGTH))
                .set(REVIEW.AUTHOR, r.author?.take(AUTHOR_LENGTH))
                .set(REVIEW.STARS, r.stars)
                .set(REVIEW.CONTENT, r.content)
                .set(REVIEW.POSTED_AT, r.postedAt)
                .set(REVIEW.TEXT_KEY, key)
                .onDuplicateKeyUpdate()
                .set(REVIEW.STARS, r.stars)
                .set(REVIEW.CONTENT, r.content)
                .set(REVIEW.TEXT_KEY, key)
                .execute()
        }
        return other
    }

    fun ids(itemId: Long): Set<String> =
        db.select(REVIEW.REVIEW_ID).from(REVIEW).where(REVIEW.ITEM_ID.eq(itemId)).fetchSet { it.value1()!! }

    /** The reviews of an item, newest first. */
    fun ofItem(itemId: Long): List<StoredReview> =
        db.selectFrom(REVIEW).where(REVIEW.ITEM_ID.eq(itemId)).orderBy(REVIEW.POSTED_AT.desc().nullsLast()).fetch {
            StoredReview(it.itemId!!, it.reviewId!!, it.author, it.stars, it.content!!, it.postedAt)
        }

    /** The texts of the reviews [refs] (item, review). */
    fun contents(refs: Collection<Pair<Long, String>>): Map<Pair<Long, String>, String> {
        val out = HashMap<Pair<Long, String>, String>()
        refs
            .map { it.first }
            .distinct()
            .chunked(BATCH)
            .forEach { chunk ->
                db.select(REVIEW.ITEM_ID, REVIEW.REVIEW_ID, REVIEW.CONTENT)
                    .from(REVIEW)
                    .where(REVIEW.ITEM_ID.`in`(chunk))
                    .fetch { r ->
                        (r.value1()!! to r.value2()!!).takeIf { it in refs }?.let { out[it] = r.value3()!! }
                    }
            }
        return out
    }

    /** The texts of the reviews of the items, as the model reads them, by item. */
    fun textsOf(itemIds: Collection<Long>): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        itemIds.distinct().chunked(BATCH).forEach { ids ->
            db.select(REVIEW.ITEM_ID, REVIEW.CONTENT).from(REVIEW).where(REVIEW.ITEM_ID.`in`(ids)).fetch {
                textOf(it.value2()!!)
                    .takeIf { t -> t.isNotEmpty() }
                    ?.let { t -> out.getOrPut(it.value1()!!) { ArrayList() } += t }
            }
        }
        return out
    }

    /**
     * Every item's reviews that have a vector by [encoder], streamed item by item as (review id, vector): the reviews
     * joined with the encoded texts by the key of their text, in one pass.
     */
    fun forEachItemVectors(encoder: String, action: (itemId: Long, reviews: List<Pair<String, FloatArray>>) -> Unit) {
        var current: Long? = null
        val list = ArrayList<Pair<String, FloatArray>>()
        db.select(REVIEW.ITEM_ID, REVIEW.REVIEW_ID, PHRASE_VECTOR.VEC)
            .from(REVIEW)
            .join(PHRASE_VECTOR)
            .on(PHRASE_VECTOR.TEXT_KEY.eq(REVIEW.TEXT_KEY))
            .where(PHRASE_VECTOR.ENCODER.eq(encoder))
            .orderBy(REVIEW.ITEM_ID)
            .fetchSize(FETCH_ROWS)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, list.toList()) }
                        list.clear()
                        current = it.value1()
                    }
                    list += it.value2()!! to Vectors.fromHalf(it.value3()!!)
                }
            }
        current?.let { action(it, list.toList()) }
    }

    /** Reviews with a text and its key: those whose vector the model can find. A range, which the index answers. */
    fun countKeyed(): Int = db.fetchCount(REVIEW, REVIEW.TEXT_KEY.ge(Long.MIN_VALUE))

    /** How many reviews of every item are stored. */
    /** How many reviews the items [ids] have: item to it, of those of any. */
    fun countsOf(ids: Collection<Long>): Map<Long, Int> =
        ids.distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(REVIEW.ITEM_ID, DSL.count())
                    .from(REVIEW)
                    .where(REVIEW.ITEM_ID.`in`(chunk))
                    .groupBy(REVIEW.ITEM_ID)
                    .fetch()
            }
            .associate { it.value1()!! to it.value2()!! }

    /** How many reviews the source's items have in all: counted by the database, no row read. */
    fun total(): Long = db.fetchCount(REVIEW).toLong()

    fun countOf(itemId: Long): Int = db.fetchCount(REVIEW, REVIEW.ITEM_ID.eq(itemId))

    /** [n] reviews of the catalogue drawn at random, as the model reads them. */
    fun sample(n: Int): List<String> =
        db.select(REVIEW.CONTENT)
            .from(REVIEW)
            .orderBy(DSL.rand())
            .limit(n)
            .fetch { textOf(it.value1()!!) }
            .filter { it.isNotEmpty() }

    companion object {
        private const val BATCH = 500

        /** The longest id of a review its column holds. */
        private const val ID_LENGTH = 100

        /** The longest name of a review's author its column holds. */
        private const val AUTHOR_LENGTH = 300

        /** The rows read from the database at a time while streaming: memory, not meaning. */
        private const val FETCH_ROWS = 5000

        /**
         * A review as the model reads it: WHOLE, one vector — an opinion is read with what is said around it ("the NTR
         * route ruined it" and "the art is great" mean what they mean only together); a long one in windows, as the
         * texts are. Only the spacing is evened out.
         */
        fun textOf(content: String): String = content.lines().joinToString("\n") { it.trim() }.trim()

        /** The key the review's text is encoded under; null for a review with no text. */
        fun textKeyOf(content: String): Long? = textOf(content).takeIf { it.isNotEmpty() }?.let(Vectors::keyOf)
    }
}
