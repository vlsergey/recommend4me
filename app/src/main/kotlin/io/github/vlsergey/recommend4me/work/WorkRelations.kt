package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK_RELATION
import java.time.Instant
import org.jooq.DSLContext

/**
 * The user's word on two normalized works that stay two, in the dictionaries: different works, or related of a kind (a
 * translation, a part). A pair is kept the smaller id first ([pair]).
 */
class WorkRelations(private val db: DSLContext) {

    /** The user's word on the two works, in place of the one before. */
    fun say(a: Long, b: Long, kind: String, now: Instant) {
        val (first, second) = pair(a, b)
        val r = NORMALIZED_WORK_RELATION
        db.insertInto(r)
            .set(r.FIRST_WORK, first)
            .set(r.OTHER_WORK, second)
            .set(r.KIND, kind)
            .set(r.SAID_AT, now)
            .onDuplicateKeyUpdate()
            .set(r.KIND, kind)
            .set(r.SAID_AT, now)
            .execute()
    }

    /** The word on the two works taken back. */
    fun takeBack(a: Long, b: Long) {
        val (first, second) = pair(a, b)
        val r = NORMALIZED_WORK_RELATION
        db.deleteFrom(r).where(r.FIRST_WORK.eq(first), r.OTHER_WORK.eq(second)).execute()
    }

    /** The word on the two works; none when there is none. */
    fun word(a: Long, b: Long): String? {
        val (first, second) = pair(a, b)
        val r = NORMALIZED_WORK_RELATION
        return db.select(r.KIND).from(r).where(r.FIRST_WORK.eq(first), r.OTHER_WORK.eq(second)).fetchOne()?.value1()
    }

    /** Every word on two works, the smaller id first. */
    fun words(): Map<Pair<Long, Long>, String> {
        val r = NORMALIZED_WORK_RELATION
        return db.select(r.FIRST_WORK, r.OTHER_WORK, r.KIND)
            .from(r)
            .fetchMap({ it.value1()!! to it.value2()!! }, { it.value3()!! })
    }

    companion object {
        /** The two works in the order a word on them is kept: the smaller id first. */
        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
