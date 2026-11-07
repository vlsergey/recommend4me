package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FIELD_CORRECTION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE USER'S OWN CONTENTS OF A WORK'S FIELDS over the site's — its title, a text, a number ([Corrected]) — as the items
 * of one source see them: every item of a work has them alike.
 */
interface FieldCorrections {
    /** The [field] of the work of [itemId] given the [content]. */
    fun setField(itemId: String, field: String, content: String, now: Instant)

    /** The user's content of the [field] of the work of [itemId] taken back: the site's stands. */
    fun unsetField(itemId: String, field: String)

    /** The fields the user gave the work of [itemId]: field to content. */
    fun fieldsOf(itemId: String): Map<String, String>

    /** The fields the user gave the works of the items [ids]: item to field to content. */
    fun fieldsOf(ids: Collection<String>): Map<String, Map<String, String>>

    /** Every field correction: item to field to content. */
    fun allFields(): Map<String, Map<String, String>>
}

/** The [FieldCorrections] of the normalized works, in the dictionaries, as the items of the [source] see them. */
internal class WorkFieldCorrections(
    private val db: DSLContext,
    private val source: String,
    private val works: WorkRepository,
) : FieldCorrections {

    override fun setField(itemId: String, field: String, content: String, now: Instant) {
        val f = WORK_FIELD_CORRECTION
        db.insertInto(f)
            .set(f.WORK, works.of(ItemKey(source, itemId), now))
            .set(f.FIELD, field)
            .set(f.CONTENT, content)
            .set(f.CORRECTED_AT, now)
            .onDuplicateKeyUpdate()
            .set(f.CONTENT, content)
            .set(f.CORRECTED_AT, now)
            .execute()
    }

    override fun unsetField(itemId: String, field: String) {
        val f = WORK_FIELD_CORRECTION
        db.deleteFrom(f).where(f.WORK.eq(works.of(ItemKey(source, itemId), Instant.now())), f.FIELD.eq(field)).execute()
    }

    /** The fields of the source's items that meet the [condition]: item to field to content. */
    private fun seenFields(condition: Condition): Map<String, Map<String, String>> {
        val f = WORK_FIELD_CORRECTION
        return db.select(SITE_WORK.ITEM, f.FIELD, f.CONTENT)
            .from(SITE_WORK)
            .join(f)
            .on(f.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! to it.value3()!! })
            .mapValues { it.value.toMap() }
    }

    override fun fieldsOf(itemId: String): Map<String, String> = fieldsOf(listOf(itemId))[itemId].orEmpty()

    override fun fieldsOf(ids: Collection<String>): Map<String, Map<String, String>> =
        ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk ->
            out.apply { putAll(seenFields(SITE_WORK.ITEM.`in`(chunk))) }
        }

    override fun allFields(): Map<String, Map<String, String>> = seenFields(DSL.noCondition())

    private companion object {
        /** The ids of a query: memory, not meaning. */
        const val BATCH = 1000
    }
}
