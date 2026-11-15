package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.author.AuthorRepository.Companion.pair
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_DISTINCT
import java.time.Instant
import org.jooq.DSLContext

/**
 * The pairs of normalized authors the user told apart — or said are partly one, a part of the works of one the other's
 * — in the dictionaries: never offered as one again, never merged by a place an author's page lists.
 */
class AuthorDistinctions(private val db: DSLContext) {

    /** The two told apart — or, [partly], a part of the works of one the other's — in place of the word said before. */
    fun tell(a: Long, b: Long, partly: Boolean, now: Instant) {
        val (first, second) = pair(a, b)
        val d = NORMALIZED_AUTHOR_DISTINCT
        db.insertInto(d)
            .set(d.FIRST_AUTHOR, first)
            .set(d.OTHER_AUTHOR, second)
            .set(d.PARTLY, partly)
            .set(d.SAID_AT, now)
            .onDuplicateKeyUpdate()
            .set(d.PARTLY, partly)
            .set(d.SAID_AT, now)
            .execute()
    }

    /** The word said of the two taken back. */
    fun takeBack(a: Long, b: Long) {
        val (first, second) = pair(a, b)
        val d = NORMALIZED_AUTHOR_DISTINCT
        db.deleteFrom(d).where(d.FIRST_AUTHOR.eq(first), d.OTHER_AUTHOR.eq(second)).execute()
    }

    /** Every pair told apart or said to be partly one, the smaller id first, with whether it is the latter. */
    fun told(): Map<Pair<Long, Long>, Boolean> {
        val d = NORMALIZED_AUTHOR_DISTINCT
        return db.select(d.FIRST_AUTHOR, d.OTHER_AUTHOR, d.PARTLY)
            .from(d)
            .fetchMap({ it.value1()!! to it.value2()!! }, { it.value3()!! })
    }
}
