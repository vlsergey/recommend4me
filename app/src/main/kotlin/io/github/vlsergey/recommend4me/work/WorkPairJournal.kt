package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_PAIR_JOURNAL
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext

/**
 * A word of the journal: on the two items as they were pressed, the word said ([answer]; none — the word taken back)
 * and the word the pair had before ([before]; none — no word). [restored]: written of what was kept before the journal
 * was, the word before unknown.
 */
class PairWord(
    val id: Long,
    val saidAt: Instant,
    val first: ItemKey,
    val second: ItemKey,
    val answer: String?,
    val before: String?,
    val restored: Boolean,
)

/**
 * The journal of the user's words on two items being one work or not, in the dictionaries: every word said and taken
 * back.
 */
class WorkPairJournal(private val db: DSLContext) {

    fun write(a: ItemKey, b: ItemKey, answer: String?, before: String?, now: Instant) {
        val j = WORK_PAIR_JOURNAL
        db.insertInto(j)
            .set(j.SAID_AT, now)
            .set(j.SOURCE, a.source)
            .set(j.ITEM, a.id)
            .set(j.OTHER_SOURCE, b.source)
            .set(j.OTHER_ITEM, b.id)
            .set(j.ANSWER, answer)
            .set(j.ANSWER_BEFORE, before)
            .execute()
    }

    /** Every word on two items of the [sources], the newest first. */
    fun all(sources: Collection<String>): List<PairWord> {
        val j = WORK_PAIR_JOURNAL
        return db.selectFrom(j)
            .where(j.SOURCE.`in`(sources), j.OTHER_SOURCE.`in`(sources))
            .orderBy(j.SAID_AT.desc(), j.ID.desc())
            .fetch {
                PairWord(
                    it.id!!,
                    it.saidAt!!,
                    ItemKey(it.source!!, it.item!!),
                    ItemKey(it.otherSource!!, it.otherItem!!),
                    it.answer,
                    it.answerBefore,
                    it.restored!!,
                )
            }
    }
}
