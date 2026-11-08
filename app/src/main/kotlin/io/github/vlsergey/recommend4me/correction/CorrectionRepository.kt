package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_CORRECTION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A value of a facet the user added to an item or took from it. */
data class FacetCorrection(
    val itemId: String,
    val facet: String,
    val key: String,
    val added: Boolean,
    val name: String?,
)

/**
 * The user's corrections of the normalized works (in the dictionaries), as the items of one source see them — every
 * item of a work has them alike: facet values added and taken away here; facets whose values are all named
 * ([CompleteFacets]) and fields overridden ([FieldCorrections]) each of its own.
 */
class CorrectionRepository(private val db: DSLContext, private val source: String, private val works: WorkRepository) :
    FieldCorrections by WorkFieldCorrections(db, source, works),
    CompleteFacets by WorkCompleteFacets(db, source, works) {

    private fun workOf(itemId: String, now: Instant = Instant.now()) = works.of(ItemKey(source, itemId), now)

    /** The works of the source's items: what a word on a value of the source reaches. */
    private fun ownWorks() = DSL.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source))

    fun setFacet(itemId: String, facet: String, key: String, added: Boolean, name: String?, now: Instant) {
        val f = WORK_FACET_CORRECTION
        db.insertInto(f)
            .set(f.WORK, workOf(itemId, now))
            .set(f.FACET, facet)
            .set(f.VALUE_KEY, key.take(KEY_WIDTH))
            .set(f.ADDED, added)
            .set(f.NAME, name?.take(NAME_WIDTH))
            .set(f.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(f.ADDED, added)
            .set(f.NAME, name?.take(NAME_WIDTH))
            .set(f.CORRECTED_AT, now)
            .execute()
    }

    fun unsetFacet(itemId: String, facet: String, key: String) {
        val f = WORK_FACET_CORRECTION
        db.deleteFrom(f).where(f.WORK.eq(workOf(itemId)), f.FACET.eq(facet), f.VALUE_KEY.eq(key)).execute()
    }

    /** Takes every correction of the value away, from every work of the source; returns the items that had one. */
    fun removeFacetValue(facet: String, key: String): List<String> {
        val f = WORK_FACET_CORRECTION
        val value = f.FACET.eq(facet).and(f.VALUE_KEY.eq(key))
        val items = seenFacets(value).map { it.itemId }
        db.deleteFrom(f).where(value, f.WORK.`in`(ownWorks())).execute()
        return items
    }

    private fun seenFacets(condition: Condition): List<FacetCorrection> {
        val f = WORK_FACET_CORRECTION
        return db.select(SITE_WORK.ITEM, f.FACET, f.VALUE_KEY, f.ADDED, f.NAME)
            .from(SITE_WORK)
            .join(f)
            .on(f.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition)
            .fetch { FacetCorrection(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()) }
    }

    fun facetsOf(itemId: String): List<FacetCorrection> = facetsOf(listOf(itemId))[itemId].orEmpty()

    /**
     * The corrections of the items [ids]: by their ids up to a [BATCH] of them; of more, every correction of the source
     * read once and picked from. They are few, and a query of a thousand ids walks all the source's works, 0.3-0.8 s
     * for each thousand of 75 000 works.
     */
    fun facetsOf(ids: Collection<String>): Map<String, List<FacetCorrection>> {
        val asked = ids.toHashSet()
        val read =
            if (asked.size > BATCH) allFacets().filter { it.itemId in asked }
            else seenFacets(SITE_WORK.ITEM.`in`(asked))
        return read.groupBy { it.itemId }
    }

    /**
     * The user's answers on the values [from] of a facet made answers on [to] — the values merged into it: of a work
     * answered on more than one of them, the newest answer stands. Returns the items of the source whose answers moved.
     */
    fun moveFacetValues(facet: String, from: Collection<String>, to: String): Set<String> {
        val moved = HashSet<Long>()
        db.transaction { c ->
            val tx = DSL.using(c)
            val f = WORK_FACET_CORRECTION
            val answers =
                tx.selectFrom(f).where(f.FACET.eq(facet), f.VALUE_KEY.`in`(from + to), f.WORK.`in`(ownWorks())).fetch()
            answers
                .groupBy { it.work!! }
                .forEach { (work, own) ->
                    if (own.all { it.valueKey == to }) return@forEach
                    val newest = own.maxBy { it.correctedAt!! }
                    tx.deleteFrom(f).where(f.WORK.eq(work), f.FACET.eq(facet), f.VALUE_KEY.`in`(from + to)).execute()
                    tx.insertInto(f)
                        .set(f.WORK, work)
                        .set(f.FACET, facet)
                        .set(f.VALUE_KEY, to.take(KEY_WIDTH))
                        .set(f.ADDED, newest.added)
                        .set(f.NAME, newest.name)
                        .set(f.CORRECTED_AT, newest.correctedAt)
                        .execute()
                    moved += work
                }
        }
        return moved.chunked(BATCH).flatMapTo(HashSet()) { chunk ->
            db.select(SITE_WORK.ITEM)
                .from(SITE_WORK)
                .where(SITE_WORK.SOURCE.eq(source), SITE_WORK.NORMALIZED.`in`(chunk))
                .fetch { it.value1()!! }
        }
    }

    /** Every facet correction: they are few, the user makes them by hand. */
    fun allFacets(): List<FacetCorrection> = seenFacets(DSL.noCondition())

    /**
     * The answers of every item of the source on the values [keys] of the [facet]: what holds those values; [named]
     * alone, those that name a value the user made up.
     */
    fun facetsWith(facet: String, keys: Collection<String>, named: Boolean = false): List<FacetCorrection> {
        val f = WORK_FACET_CORRECTION
        // The answers by the index of the values, then the source's items of their works: joined in one query, every
        // item of the source was walked to find them
        val answers =
            db.select(f.WORK, f.VALUE_KEY, f.ADDED, f.NAME)
                .from(f)
                .where(f.FACET.eq(facet), f.VALUE_KEY.`in`(keys), if (named) f.NAME.isNotNull else DSL.noCondition())
                .fetch()
        if (answers.isEmpty()) return emptyList()
        val items =
            db.select(SITE_WORK.NORMALIZED, SITE_WORK.ITEM)
                .from(SITE_WORK)
                .where(SITE_WORK.SOURCE.eq(source), SITE_WORK.NORMALIZED.`in`(answers.map { it.value1() }.toSet()))
                .fetchGroups(SITE_WORK.NORMALIZED, SITE_WORK.ITEM)
        return answers.flatMap { a ->
            items[a.value1()].orEmpty().map { item ->
                FacetCorrection(item!!, facet, a.value2()!!, a.value3()!!, a.value4())
            }
        }
    }

    private companion object {
        /** The ids asked for by their ids at most; of more, every answer of the source is read once and picked from. */
        const val BATCH = 1000

        /** The width of the column of a value's key (VARCHAR(500)): a longer one is cut. */
        const val KEY_WIDTH = 500

        /** The width of the column of the name the user gave a value (VARCHAR(1000)): a longer one is cut. */
        const val NAME_WIDTH = 1000
    }
}
