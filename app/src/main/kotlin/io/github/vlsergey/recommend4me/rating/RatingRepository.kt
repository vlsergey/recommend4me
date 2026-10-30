package io.github.vlsergey.recommend4me.rating

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_RATING
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.jooq.Condition
import org.jooq.DSLContext
import java.time.Instant

data class Rating(val itemId: String, val version: String, val grade: Int, val ratedAt: Instant)

/** A grade of a work as it was given: through which item, at which version, and whether it stands. */
data class GivenRating(val key: ItemKey, val version: String, val grade: Int, val ratedAt: Instant, val current: Boolean)

/**
 * The user's grades, of the normalized works (in the dictionaries), as the items of one source see
 * them. A grade is given through an item at a version; every item of the work has it, at its own
 * version while the grade stands — a work has one such — and a new release of the item it was given
 * through makes it a grade of before.
 */
class RatingRepository(private val db: DSLContext, private val source: String, private val works: WorkRepository) {

    private val through = SITE_WORK.`as`("through")

    /** The grades of the source's items by [condition], as [Rating]s of the item that sees them. */
    private fun seen(condition: Condition): List<Rating> {
        val r = WORK_RATING
        return db.select(SITE_WORK.ITEM, SITE_WORK.VERSION, r.VERSION, through.VERSION, r.GRADE, r.RATED_AT).from(SITE_WORK)
            .join(r).on(r.WORK.eq(SITE_WORK.NORMALIZED))
            .join(through).on(through.SOURCE.eq(r.SOURCE), through.ITEM.eq(r.ITEM))
            .where(SITE_WORK.SOURCE.eq(source), condition)
            .orderBy(SITE_WORK.ITEM, r.RATED_AT)
            .fetch { Rating(it.value1()!!, if (it.value3() == it.value4()) it.value2()!! else it.value3()!!, it.value5()!!, it.value6()!!) }
    }

    /** The work of the item graded at the version it stands at, in place of the grade that stood. */
    fun rate(itemId: String, version: String, grade: Int, now: Instant) {
        require(grade in Grades.RANGE) { "A grade is ${Grades.MIN}..${Grades.MAX}, not $grade" }
        val key = ItemKey(source, itemId)
        val work = works.of(key, now)
        works.setVersion(key, version)
        dropCurrent(work)
        val r = WORK_RATING
        db.insertInto(r).set(r.WORK, work).set(r.SOURCE, source).set(r.ITEM, itemId).set(r.VERSION, version).set(r.GRADE, grade).set(r.RATED_AT, now)
            .onDuplicateKeyUpdate().set(r.GRADE, grade).set(r.RATED_AT, now)
            .execute()
    }

    /** The grade at the [version] taken back: the work's own of the version the item stands at, of a release before that alone. */
    fun unrate(itemId: String, version: String) {
        val key = ItemKey(source, itemId)
        val work = works.of(key, Instant.now())
        val r = WORK_RATING
        if (version == versionOf(itemId)) dropCurrent(work) else db.deleteFrom(r).where(r.WORK.eq(work), r.VERSION.eq(version)).execute()
    }

    private fun versionOf(itemId: String): String? =
        db.select(SITE_WORK.VERSION).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source), SITE_WORK.ITEM.eq(itemId)).fetchOne()?.value1()

    private fun dropCurrent(work: Long) {
        val r = WORK_RATING
        db.select(r.SOURCE, r.ITEM, r.VERSION).from(r)
            .join(SITE_WORK).on(SITE_WORK.SOURCE.eq(r.SOURCE), SITE_WORK.ITEM.eq(r.ITEM), SITE_WORK.VERSION.eq(r.VERSION))
            .where(r.WORK.eq(work)).fetch()
            .forEach { db.deleteFrom(r).where(r.WORK.eq(work), r.SOURCE.eq(it.value1()), r.ITEM.eq(it.value2()), r.VERSION.eq(it.value3())).execute() }
    }

    fun ofItem(itemId: String): List<Rating> = seen(SITE_WORK.ITEM.eq(itemId)).sortedByDescending { it.ratedAt }

    /** Every grade of the work of the item as it was given, the newest first. */
    fun given(itemId: String): List<GivenRating> {
        val r = WORK_RATING
        val work = SITE_WORK.NORMALIZED
        return db.select(r.SOURCE, r.ITEM, r.VERSION, r.GRADE, r.RATED_AT, through.VERSION).from(SITE_WORK)
            .join(r).on(r.WORK.eq(work))
            .join(through).on(through.SOURCE.eq(r.SOURCE), through.ITEM.eq(r.ITEM))
            .where(SITE_WORK.SOURCE.eq(source), SITE_WORK.ITEM.eq(itemId))
            .orderBy(r.RATED_AT.desc())
            .fetch { GivenRating(ItemKey(it.value1()!!, it.value2()!!), it.value3()!!, it.value4()!!, it.value5()!!, it.value3() == it.value6()) }
    }

    fun all(): List<Rating> = seen(WORK_RATING.GRADE.isNotNull)

    fun gradedItems(): Set<String> = all().mapTo(HashSet()) { it.itemId }

    fun count(): Int = all().size
}
