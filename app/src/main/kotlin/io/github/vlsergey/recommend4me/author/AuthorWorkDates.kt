package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR
import io.github.vlsergey.recommend4me.manner.GroupCursor
import java.time.Instant
import org.jooq.DSLContext

/**
 * WHEN THE LISTS OF THE NORMALIZED AUTHORS' WORKS LAST CHANGED (`normalized_author.works_at`), in the dictionaries: a
 * work naming them or not any more, an author bound, merged or taken apart, a work of theirs removed — what their
 * manners are made anew by ([io.github.vlsergey.recommend4me.manner.GroupMannerChanges]), read in the order of the
 * dates and the numbers from where a reading stands.
 */
class AuthorWorkDates(private val db: DSLContext) {

    /** The lists of the works of the normalized authors [ids] changed [now]. */
    fun changed(ids: Collection<Long>, now: Instant) = changed(db, ids, now)

    /**
     * The next [limit] normalized authors after the [cursor] — of none, from the first — in the order of the changes of
     * their works' lists: by the date, then by the number; each as the cursor after it. Of no change dated, none.
     */
    fun changedAfter(cursor: GroupCursor?, limit: Int): List<GroupCursor> {
        val n = NORMALIZED_AUTHOR
        val after =
            if (cursor == null) n.WORKS_AT.ge(Instant.EPOCH)
            else n.WORKS_AT.ge(cursor.at).and(n.WORKS_AT.gt(cursor.at).or(n.ID.gt(cursor.id)))
        return db.select(n.ID, n.WORKS_AT)
            .from(n)
            .where(after)
            .orderBy(n.WORKS_AT.asc(), n.ID.asc())
            .limit(limit)
            .fetch { GroupCursor(it.value2()!!, it.value1()!!) }
    }

    companion object {
        /** The authors written by one statement: the length of a query's list, not a meaning. */
        private const val BATCH = 1000

        /** The lists of the works of the authors [ids] changed [now], within the transaction [tx]. */
        fun changed(tx: DSLContext, ids: Collection<Long>, now: Instant) =
            ids.distinct().chunked(BATCH).forEach { chunk ->
                tx.update(NORMALIZED_AUTHOR)
                    .set(NORMALIZED_AUTHOR.WORKS_AT, now)
                    .where(NORMALIZED_AUTHOR.ID.`in`(chunk))
                    .execute()
            }
    }
}
