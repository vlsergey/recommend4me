package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SERIES_GROUP
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SERIES_GROUP_WORK
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE WORKS THE USER SAID TO BE ONE SERIES, in the dictionaries: groups of normalized works, a work in one at most. A
 * group of fewer than two works is none: it goes.
 */
class SeriesGroups(
    private val db: DSLContext,
    /** Told of every work put in a group or out of it. */
    private val regrouped: () -> Unit,
) {

    /** Every group: its id to its works. */
    fun all(): Map<Long, Set<Long>> =
        db.select(SERIES_GROUP_WORK.SERIES, SERIES_GROUP_WORK.WORK)
            .from(SERIES_GROUP_WORK)
            .fetchGroups({ it.value1()!! }, { it.value2()!! })
            .mapValues { it.value.toSet() }

    /** The groups of the [works] that are in one: work to its group. */
    fun groupsOf(works: Collection<Long>): Map<Long, Long> =
        works
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SERIES_GROUP_WORK.WORK, SERIES_GROUP_WORK.SERIES)
                    .from(SERIES_GROUP_WORK)
                    .where(SERIES_GROUP_WORK.WORK.`in`(chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

    /** The works of the [groups]: group to its works. */
    fun worksOf(groups: Collection<Long>): Map<Long, Set<Long>> =
        groups
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SERIES_GROUP_WORK.SERIES, SERIES_GROUP_WORK.WORK)
                    .from(SERIES_GROUP_WORK)
                    .where(SERIES_GROUP_WORK.SERIES.`in`(chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    /**
     * The works [x] and [y] said to be one series: of a work in a group, the other put in it; of both in two, the
     * second's group joined to the first's; of neither, a group of the two made.
     */
    fun together(x: Long, y: Long, now: Instant) {
        db.transaction { c -> joined(DSL.using(c), x, y, now) }
        regrouped()
    }

    /** [together] within the transaction [tx]. */
    private fun joined(tx: DSLContext, x: Long, y: Long, now: Instant) {
        val groups = groupsIn(tx, listOf(x, y))
        val gx = groups[x]
        val gy = groups[y]
        when {
            gx != null && gy != null ->
                if (gx != gy)
                    tx.update(SERIES_GROUP_WORK)
                        .set(SERIES_GROUP_WORK.SERIES, gx)
                        .where(SERIES_GROUP_WORK.SERIES.eq(gy))
                        .execute()
            gx != null -> put(tx, y, gx, now)
            gy != null -> put(tx, x, gy, now)
            else -> {
                val id =
                    tx.insertInto(SERIES_GROUP)
                        .set(SERIES_GROUP.MADE_AT, now)
                        .returningResult(SERIES_GROUP.ID)
                        .fetchOne()!!
                        .value1()!!
                put(tx, x, id, now)
                put(tx, y, id, now)
            }
        }
        dropSmall(tx)
    }

    /** The [works] out of their groups: the user's word of them taken back. */
    fun leave(works: Collection<Long>) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(SERIES_GROUP_WORK).where(SERIES_GROUP_WORK.WORK.`in`(works.distinct())).execute()
            dropSmall(tx)
        }
        regrouped()
    }

    /** Whether the two works are of one group. */
    fun oneGroup(x: Long, y: Long): Boolean = groupsOf(listOf(x, y)).let { it[x] != null && it[x] == it[y] }

    private fun put(tx: DSLContext, work: Long, group: Long, now: Instant) {
        tx.insertInto(SERIES_GROUP_WORK)
            .set(SERIES_GROUP_WORK.WORK, work)
            .set(SERIES_GROUP_WORK.SERIES, group)
            .set(SERIES_GROUP_WORK.SAID_AT, now)
            .execute()
    }

    companion object {
        /** The works read by one statement: the length of a query's list, not a meaning. */
        private const val BATCH = 500

        private fun groupsIn(tx: DSLContext, works: Collection<Long>): Map<Long, Long> =
            tx.select(SERIES_GROUP_WORK.WORK, SERIES_GROUP_WORK.SERIES)
                .from(SERIES_GROUP_WORK)
                .where(SERIES_GROUP_WORK.WORK.`in`(works))
                .fetchMap({ it.value1()!! }, { it.value2()!! })

        /** Every group of fewer than two works gone. */
        private fun dropSmall(tx: DSLContext) {
            val small =
                tx.select(SERIES_GROUP.ID)
                    .from(SERIES_GROUP)
                    .leftJoin(SERIES_GROUP_WORK)
                    .on(SERIES_GROUP_WORK.SERIES.eq(SERIES_GROUP.ID))
                    .groupBy(SERIES_GROUP.ID)
                    .having(DSL.count(SERIES_GROUP_WORK.WORK).lt(2))
                    .fetch { it.value1()!! }
            if (small.isNotEmpty()) tx.deleteFrom(SERIES_GROUP).where(SERIES_GROUP.ID.`in`(small)).execute()
        }

        /**
         * Of the normalized work [from] merged into [into], in the transaction [tx] of the merge: its group the group
         * of [into] — of both in two, the groups one.
         */
        fun merged(tx: DSLContext, from: Long, into: Long) {
            val groups = groupsIn(tx, listOf(from, into))
            val gf = groups[from] ?: return
            val gi = groups[into]
            if (gi == null)
                tx.update(SERIES_GROUP_WORK)
                    .set(SERIES_GROUP_WORK.WORK, into)
                    .where(SERIES_GROUP_WORK.WORK.eq(from))
                    .execute()
            else {
                tx.deleteFrom(SERIES_GROUP_WORK).where(SERIES_GROUP_WORK.WORK.eq(from)).execute()
                if (gf != gi)
                    tx.update(SERIES_GROUP_WORK)
                        .set(SERIES_GROUP_WORK.SERIES, gi)
                        .where(SERIES_GROUP_WORK.SERIES.eq(gf))
                        .execute()
            }
            dropSmall(tx)
        }
    }
}
