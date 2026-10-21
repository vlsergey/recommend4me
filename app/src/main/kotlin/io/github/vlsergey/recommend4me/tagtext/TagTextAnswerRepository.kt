package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.database.corrections.tables.references.TAG_TEXT_ANSWER
import io.github.vlsergey.recommend4me.part.Paragraphs
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** The user's word on a tag offered by a paragraph of a work's text ([TextTags]). */
enum class TagTextVerdict {
    /** The work has the tag by this paragraph: answered on the work beside, and learnt from. */
    YES,

    /** The paragraph is of the tag, not enough for the work to have it: offered so no more, nothing learnt. */
    ENOUGH_NOT,

    /** The text and the tag are of different things: learnt from. */
    WRONG,
}

/**
 * An answer on a tag and a paragraph — known by the [hash] of its text, the part and the number
 * telling where it was answered — with their closeness as it was then, and the [cutting] it was given on.
 */
data class TagTextAnswer(
    val itemId: String, val tag: Long, val partId: String, val paragraph: Int, val hash: String?, val verdict: TagTextVerdict, val closeness: Float,
    val cutting: Int = Paragraphs.CUTTING,
)

/**
 * The user's answers on the tags offered by the paragraphs of the works of one source: the user's own
 * word, kept with the corrections, bound to the paragraph by the hash of its text. Each of the cutting
 * into paragraphs it was given on: the model learns from those of the cutting in use alone.
 */
class TagTextAnswerRepository(private val db: DSLContext) {

    /** Every answer on the item's tags by its text, of any cutting: a paragraph answered on is known by its hash. */
    fun ofItem(itemId: String): List<TagTextAnswer> =
        db.selectFrom(TAG_TEXT_ANSWER).where(TAG_TEXT_ANSWER.ITEM_ID.eq(itemId)).fetch { it.toAnswer() }

    /** Every answer of the source but those that teach nothing ([TagTextVerdict.ENOUGH_NOT]): what the model of the closeness learns from. */
    fun teaching(): List<TagTextAnswer> =
        db.selectFrom(TAG_TEXT_ANSWER)
            .where(TAG_TEXT_ANSWER.VERDICT.ne(TagTextVerdict.ENOUGH_NOT.name), TAG_TEXT_ANSWER.CUTTING.eq(Paragraphs.CUTTING))
            .fetch { it.toAnswer() }

    /** The answers given before they were bound to their paragraphs' hashes: to bind ([bind]). */
    fun unbound(): List<TagTextAnswer> = db.selectFrom(TAG_TEXT_ANSWER).where(TAG_TEXT_ANSWER.PARAGRAPH_HASH.isNull).fetch { it.toAnswer() }

    /** The answer [a] bound to the paragraph of the [hash]. */
    fun bind(a: TagTextAnswer, hash: String) {
        db.update(TAG_TEXT_ANSWER).set(TAG_TEXT_ANSWER.PARAGRAPH_HASH, hash)
            .where(TAG_TEXT_ANSWER.ITEM_ID.eq(a.itemId), TAG_TEXT_ANSWER.TAG.eq(a.tag), TAG_TEXT_ANSWER.PART_ID.eq(a.partId), TAG_TEXT_ANSWER.PARAGRAPH_NO.eq(a.paragraph))
            .execute()
    }

    /** The answer kept, one given before on the same tag and paragraph — by its hash, or where it is now — replaced. */
    fun answer(a: TagTextAnswer, now: Instant) {
        db.transaction { c ->
            val tx = DSL.using(c)
            val t = TAG_TEXT_ANSWER
            val samePlace = t.PART_ID.eq(a.partId).and(t.PARAGRAPH_NO.eq(a.paragraph))
            tx.deleteFrom(t).where(t.ITEM_ID.eq(a.itemId), t.TAG.eq(a.tag), a.hash?.let { samePlace.or(t.PARAGRAPH_HASH.eq(it)) } ?: samePlace).execute()
            tx.insertInto(t)
                .set(t.ITEM_ID, a.itemId).set(t.TAG, a.tag).set(t.PART_ID, a.partId).set(t.PARAGRAPH_NO, a.paragraph).set(t.PARAGRAPH_HASH, a.hash)
                .set(t.VERDICT, a.verdict.name).set(t.CLOSENESS, a.closeness).set(t.SAID_AT, now).set(t.CUTTING, a.cutting)
                .execute()
        }
    }

    private fun org.jooq.Record.toAnswer() = TagTextAnswer(
        get(TAG_TEXT_ANSWER.ITEM_ID)!!, get(TAG_TEXT_ANSWER.TAG)!!, get(TAG_TEXT_ANSWER.PART_ID)!!, get(TAG_TEXT_ANSWER.PARAGRAPH_NO)!!,
        get(TAG_TEXT_ANSWER.PARAGRAPH_HASH), TagTextVerdict.valueOf(get(TAG_TEXT_ANSWER.VERDICT)!!), get(TAG_TEXT_ANSWER.CLOSENESS)!!,
        get(TAG_TEXT_ANSWER.CUTTING)!!,
    )
}
