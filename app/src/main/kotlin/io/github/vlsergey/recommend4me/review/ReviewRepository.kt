package io.github.vlsergey.recommend4me.review

import io.github.vlsergey.recommend4me.database.source.tables.references.PHRASE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.REVIEW
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

data class StoredReview(
    val itemId: String,
    val reviewId: String,
    val author: String?,
    val stars: Double?,
    val content: String,
    val postedAt: Instant?,
)

/** The opinions of other people about the items of one source: reviews, comments. */
class ReviewRepository(private val db: DSLContext) {

    /** Stores the reviews, a known one updated (its author may have edited it). */
    fun save(itemId: String, reviews: List<ReviewData>) {
        reviews.forEach { r ->
            val key = textKeyOf(r.content)
            db.insertInto(REVIEW)
                .set(REVIEW.ITEM_ID, itemId)
                .set(REVIEW.REVIEW_ID, r.id.take(100))
                .set(REVIEW.AUTHOR, r.author?.take(300))
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
    }

    fun ids(itemId: String): Set<String> =
        db.select(REVIEW.REVIEW_ID).from(REVIEW).where(REVIEW.ITEM_ID.eq(itemId)).fetchSet { it.value1()!! }

    /** The reviews of an item, newest first. */
    fun ofItem(itemId: String): List<StoredReview> =
        db.selectFrom(REVIEW).where(REVIEW.ITEM_ID.eq(itemId)).orderBy(REVIEW.POSTED_AT.desc().nullsLast())
            .fetch { StoredReview(it.itemId!!, it.reviewId!!, it.author, it.stars, it.content!!, it.postedAt) }

    /** The texts of the reviews [refs] (item, review). */
    fun contents(refs: Collection<Pair<String, String>>): Map<Pair<String, String>, String> {
        val out = HashMap<Pair<String, String>, String>()
        refs.map { it.first }.distinct().chunked(BATCH).forEach { chunk ->
            db.select(REVIEW.ITEM_ID, REVIEW.REVIEW_ID, REVIEW.CONTENT).from(REVIEW).where(REVIEW.ITEM_ID.`in`(chunk))
                .fetch { r -> (r.value1()!! to r.value2()!!).takeIf { it in refs }?.let { out[it] = r.value3()!! } }
        }
        return out
    }

    /** The texts of the reviews of the items, as the model reads them, by item. */
    fun textsOf(itemIds: Collection<String>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        itemIds.distinct().chunked(BATCH).forEach { ids ->
            db.select(REVIEW.ITEM_ID, REVIEW.CONTENT).from(REVIEW).where(REVIEW.ITEM_ID.`in`(ids))
                .fetch { textOf(it.value2()!!).takeIf { t -> t.isNotEmpty() }?.let { t -> out.getOrPut(it.value1()!!) { ArrayList() } += t } }
        }
        return out
    }

    /**
     * Every item's reviews that have a vector by [encoder], streamed item by item as (review id,
     * vector): the reviews joined with the encoded texts by the key of their text, in one pass.
     */
    fun forEachItemVectors(encoder: String, action: (itemId: String, reviews: List<Pair<String, FloatArray>>) -> Unit) {
        var current: String? = null
        val list = ArrayList<Pair<String, FloatArray>>()
        db.select(REVIEW.ITEM_ID, REVIEW.REVIEW_ID, PHRASE_VECTOR.VEC).from(REVIEW)
            .join(PHRASE_VECTOR).on(PHRASE_VECTOR.TEXT_KEY.eq(REVIEW.TEXT_KEY))
            .where(PHRASE_VECTOR.ENCODER.eq(encoder))
            .orderBy(REVIEW.ITEM_ID)
            .fetchSize(5000).fetchLazy().use { cursor ->
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

    fun count(): Int = db.fetchCount(REVIEW)

    /** Reviews with a text and its key: those whose vector the model can find. A range, which the index answers. */
    fun countKeyed(): Int = db.fetchCount(REVIEW, REVIEW.TEXT_KEY.ge(Long.MIN_VALUE))

    /** How many reviews of every item are stored. */
    fun counts(): Map<String, Int> =
        db.select(REVIEW.ITEM_ID, DSL.count()).from(REVIEW).groupBy(REVIEW.ITEM_ID)
            .fetch().associate { it.value1()!! to it.value2()!! }

    fun countOf(itemId: String): Int = db.fetchCount(REVIEW, REVIEW.ITEM_ID.eq(itemId))

    /** [n] reviews of the catalogue drawn at random, as the model reads them. */
    fun sample(n: Int): List<String> =
        db.select(REVIEW.CONTENT).from(REVIEW).orderBy(DSL.rand()).limit(n).fetch { textOf(it.value1()!!) }.filter { it.isNotEmpty() }

    /** The texts of the reviews whose text has no vector by [encoder] yet, at most [limit]. */
    fun unencoded(encoder: String, limit: Int): List<String> =
        db.select(REVIEW.CONTENT).from(REVIEW)
            .where(REVIEW.TEXT_KEY.isNotNull)
            .andNotExists(DSL.selectOne().from(PHRASE_VECTOR).where(PHRASE_VECTOR.TEXT_KEY.eq(REVIEW.TEXT_KEY), PHRASE_VECTOR.ENCODER.eq(encoder)))
            .limit(limit)
            .fetch { textOf(it.value1()!!) }

    companion object {
        private const val BATCH = 500

        /**
         * A review as the model reads it: WHOLE, one vector — an opinion is read with what is said
         * around it ("the NTR route ruined it" and "the art is great" mean what they mean only
         * together); a long one in windows, as the texts are. Only the spacing is evened out.
         */
        fun textOf(content: String): String = content.lines().joinToString("\n") { it.trim() }.trim()

        /** The key the review's text is encoded under; null for a review with no text. */
        fun textKeyOf(content: String): Long? = textOf(content).takeIf { it.isNotEmpty() }?.let(Vectors::keyOf)
    }
}
