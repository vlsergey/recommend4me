package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.database.corrections.tables.references.TAG_TEXT_ANSWER
import io.github.vlsergey.recommend4me.part.Paragraphs
import org.jooq.DSLContext
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

/** An answer on a tag and a paragraph — a part's, by its number — with their closeness as it was then. */
data class TagTextAnswer(val itemId: String, val tag: Long, val partId: String, val paragraph: Int, val verdict: TagTextVerdict, val closeness: Float)

/**
 * The user's answers on the tags offered by the paragraphs of the works of one source: the user's own
 * word, kept with the corrections. Each of the cutting into paragraphs it was given on ([Paragraphs.VERSION]):
 * those of another cutting are of other paragraphs — kept, but read by nothing here.
 */
class TagTextAnswerRepository(private val db: DSLContext) {

    fun ofItem(itemId: String): List<TagTextAnswer> =
        db.selectFrom(TAG_TEXT_ANSWER).where(TAG_TEXT_ANSWER.ITEM_ID.eq(itemId), TAG_TEXT_ANSWER.CUTTING.eq(Paragraphs.VERSION)).fetch { it.toAnswer() }

    /** Every answer of the source but those that teach nothing ([TagTextVerdict.ENOUGH_NOT]): what the model of the closeness learns from. */
    fun teaching(): List<TagTextAnswer> =
        db.selectFrom(TAG_TEXT_ANSWER)
            .where(TAG_TEXT_ANSWER.VERDICT.ne(TagTextVerdict.ENOUGH_NOT.name), TAG_TEXT_ANSWER.CUTTING.eq(Paragraphs.VERSION))
            .fetch { it.toAnswer() }

    /** The answer kept, one given before on the same tag and paragraph replaced. */
    fun answer(a: TagTextAnswer, now: Instant) {
        db.mergeInto(TAG_TEXT_ANSWER)
            .using(db.selectOne())
            .on(TAG_TEXT_ANSWER.ITEM_ID.eq(a.itemId), TAG_TEXT_ANSWER.TAG.eq(a.tag), TAG_TEXT_ANSWER.PART_ID.eq(a.partId), TAG_TEXT_ANSWER.PARAGRAPH_NO.eq(a.paragraph))
            .whenMatchedThenUpdate().set(TAG_TEXT_ANSWER.VERDICT, a.verdict.name).set(TAG_TEXT_ANSWER.CLOSENESS, a.closeness).set(TAG_TEXT_ANSWER.SAID_AT, now)
            .set(TAG_TEXT_ANSWER.CUTTING, Paragraphs.VERSION)
            .whenNotMatchedThenInsert(
                TAG_TEXT_ANSWER.ITEM_ID, TAG_TEXT_ANSWER.TAG, TAG_TEXT_ANSWER.PART_ID, TAG_TEXT_ANSWER.PARAGRAPH_NO,
                TAG_TEXT_ANSWER.VERDICT, TAG_TEXT_ANSWER.CLOSENESS, TAG_TEXT_ANSWER.SAID_AT, TAG_TEXT_ANSWER.CUTTING,
            )
            .values(a.itemId, a.tag, a.partId, a.paragraph, a.verdict.name, a.closeness, now, Paragraphs.VERSION)
            .execute()
    }

    private fun org.jooq.Record.toAnswer() = TagTextAnswer(
        get(TAG_TEXT_ANSWER.ITEM_ID)!!, get(TAG_TEXT_ANSWER.TAG)!!, get(TAG_TEXT_ANSWER.PART_ID)!!, get(TAG_TEXT_ANSWER.PARAGRAPH_NO)!!,
        TagTextVerdict.valueOf(get(TAG_TEXT_ANSWER.VERDICT)!!), get(TAG_TEXT_ANSWER.CLOSENESS)!!,
    )
}
