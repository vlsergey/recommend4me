package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.FACET_COMPLETE
import io.github.vlsergey.recommend4me.database.corrections.tables.references.FACET_CORRECTION
import io.github.vlsergey.recommend4me.database.corrections.tables.references.FIELD_CORRECTION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_COMPLETE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_CORRECTION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FIELD_CORRECTION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A value of a facet the user added to an item or took from it. */
data class FacetCorrection(val itemId: String, val facet: String, val key: String, val added: Boolean, val name: String?)

/**
 * The user's corrections of the normalized works (in the dictionaries), as the items of one source
 * see them — every item of a work has them alike: facet values added and taken away, facets whose
 * values are all named, fields overridden.
 */
class CorrectionRepository(
    private val db: DSLContext,
    private val source: String,
    private val works: WorkRepository,
    /** The corrections of the source, where the user's word was kept before the normalized works. */
    private val before: DSLContext,
) {

    private fun workOf(itemId: String, now: Instant = Instant.now()) = works.of(ItemKey(source, itemId), now)

    /** The works of the source's items: what a word on a value of the source reaches. */
    private fun ownWorks() = DSL.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source))

    fun setFacet(itemId: String, facet: String, key: String, added: Boolean, name: String?, now: Instant) {
        val f = WORK_FACET_CORRECTION
        db.insertInto(f)
            .set(f.WORK, workOf(itemId, now)).set(f.FACET, facet).set(f.VALUE_KEY, key.take(500))
            .set(f.ADDED, added).set(f.NAME, name?.take(1000)).set(f.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(f.ADDED, added).set(f.NAME, name?.take(1000)).set(f.CORRECTED_AT, now)
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
        return db.select(SITE_WORK.ITEM, f.FACET, f.VALUE_KEY, f.ADDED, f.NAME).from(SITE_WORK).join(f).on(f.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition)
            .fetch { FacetCorrection(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()) }
    }

    fun facetsOf(itemId: String): List<FacetCorrection> = facetsOf(listOf(itemId))[itemId].orEmpty()

    fun facetsOf(ids: Collection<String>): Map<String, List<FacetCorrection>> =
        ids.distinct().chunked(BATCH).flatMap { seenFacets(SITE_WORK.ITEM.`in`(it)) }.groupBy { it.itemId }

    /**
     * The user's answers on the values [from] of a facet made answers on [to] — the values merged
     * into it: of a work answered on more than one of them, the newest answer stands. Returns the
     * items of the source whose answers moved.
     */
    fun moveFacetValues(facet: String, from: Collection<String>, to: String): Set<String> {
        val moved = HashSet<Long>()
        db.transaction { c ->
            val tx = DSL.using(c)
            val f = WORK_FACET_CORRECTION
            val answers = tx.selectFrom(f).where(f.FACET.eq(facet), f.VALUE_KEY.`in`(from + to), f.WORK.`in`(ownWorks())).fetch()
            answers.groupBy { it.work!! }.forEach { (work, own) ->
                if (own.all { it.valueKey == to }) return@forEach
                val newest = own.maxBy { it.correctedAt!! }
                tx.deleteFrom(f).where(f.WORK.eq(work), f.FACET.eq(facet), f.VALUE_KEY.`in`(from + to)).execute()
                tx.insertInto(f)
                    .set(f.WORK, work).set(f.FACET, facet).set(f.VALUE_KEY, to.take(500))
                    .set(f.ADDED, newest.added).set(f.NAME, newest.name).set(f.CORRECTED_AT, newest.correctedAt)
                    .execute()
                moved += work
            }
        }
        return moved.chunked(BATCH).flatMapTo(HashSet()) { chunk ->
            db.select(SITE_WORK.ITEM).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source), SITE_WORK.NORMALIZED.`in`(chunk)).fetch { it.value1()!! }
        }
    }

    /** Every facet correction: they are few, the user makes them by hand. */
    fun allFacets(): List<FacetCorrection> = seenFacets(DSL.noCondition())

    fun setField(itemId: String, field: String, content: String, now: Instant) {
        val f = WORK_FIELD_CORRECTION
        db.insertInto(f).set(f.WORK, workOf(itemId, now)).set(f.FIELD, field).set(f.CONTENT, content).set(f.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(f.CONTENT, content).set(f.CORRECTED_AT, now)
            .execute()
    }

    fun unsetField(itemId: String, field: String) {
        val f = WORK_FIELD_CORRECTION
        db.deleteFrom(f).where(f.WORK.eq(workOf(itemId)), f.FIELD.eq(field)).execute()
    }

    private fun seenFields(condition: Condition): Map<String, Map<String, String>> {
        val f = WORK_FIELD_CORRECTION
        return db.select(SITE_WORK.ITEM, f.FIELD, f.CONTENT).from(SITE_WORK).join(f).on(f.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! }).mapValues { it.value.toMap() }
    }

    fun fieldsOf(itemId: String): Map<String, String> = fieldsOf(listOf(itemId))[itemId].orEmpty()

    fun fieldsOf(ids: Collection<String>): Map<String, Map<String, String>> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk -> out.apply { putAll(seenFields(SITE_WORK.ITEM.`in`(chunk))) } }

    /** Every field correction: item to field to content. */
    fun allFields(): Map<String, Map<String, String>> = seenFields(DSL.noCondition())

    // --- Facets whose values are all named ---

    /** The user's word that the work's values of the facet are all named ([complete]), or its taking back. */
    fun setComplete(itemId: String, facet: String, complete: Boolean, now: Instant) {
        val c = WORK_FACET_COMPLETE
        if (complete) db.insertInto(c).set(c.WORK, workOf(itemId, now)).set(c.FACET, facet).set(c.COMPLETED_AT, now).onDuplicateKeyIgnore().execute()
        else db.deleteFrom(c).where(c.WORK.eq(workOf(itemId, now)), c.FACET.eq(facet)).execute()
    }

    private fun seenComplete(condition: Condition): Map<String, Set<String>> {
        val c = WORK_FACET_COMPLETE
        return db.select(SITE_WORK.ITEM, c.FACET).from(SITE_WORK).join(c).on(c.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition).fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! }).mapValues { it.value.toSet() }
    }

    /** The facets whose values the user said are all named, of each of the items [ids] that has any. */
    fun completeOf(ids: Collection<String>): Map<String, Set<String>> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk -> out.apply { putAll(seenComplete(SITE_WORK.ITEM.`in`(chunk))) } }

    fun completeOf(itemId: String): Set<String> = completeOf(listOf(itemId))[itemId].orEmpty()

    /** Every item with a facet whose values are all named: they are few, the user says it by hand. */
    fun allComplete(): Map<String, Set<String>> = seenComplete(DSL.noCondition())

    /** The answers kept before the normalized works given to them, the newest of a work's standing, and taken from there. */
    fun moveToWorks(now: Instant): Int {
        val facets = before.selectFrom(FACET_CORRECTION).orderBy(FACET_CORRECTION.CORRECTED_AT).fetch()
        val fields = before.selectFrom(FIELD_CORRECTION).orderBy(FIELD_CORRECTION.CORRECTED_AT).fetch()
        val complete = before.selectFrom(FACET_COMPLETE).fetch()
        facets.forEach { setFacet(it.itemId!!, it.facet!!, it.valueKey!!, it.added!!, it.name, it.correctedAt!!) }
        fields.forEach { setField(it.itemId!!, it.field!!, it.content!!, it.correctedAt!!) }
        complete.forEach { setComplete(it.itemId!!, it.facet!!, true, it.completedAt!!) }
        before.deleteFrom(FACET_CORRECTION).execute()
        before.deleteFrom(FIELD_CORRECTION).execute()
        before.deleteFrom(FACET_COMPLETE).execute()
        return facets.size + fields.size + complete.size
    }

    companion object {
        private const val BATCH = 1000
    }
}
