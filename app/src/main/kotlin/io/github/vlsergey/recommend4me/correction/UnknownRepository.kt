package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_UNKNOWN
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.jooq.DSLContext
import java.time.Instant

/** The user's word that whether the work [itemId] has the value [key] of the [facet] is not clear of it. */
data class Unknown(val itemId: String, val facet: String, val key: String, val saidAt: Instant)

/**
 * The user's word, of the normalized works (in the dictionaries) as the items of one source see them,
 * that whether a work has a value is not clear of it — apart from the corrections: the value is asked
 * of the work no more, and nothing learns of it; the work's values stay as they were.
 */
class UnknownRepository(private val db: DSLContext, private val source: String, private val works: WorkRepository) {

    fun say(itemId: String, facet: String, key: String, now: Instant) {
        val u = WORK_FACET_UNKNOWN
        db.insertInto(u).set(u.WORK, works.of(ItemKey(source, itemId), now)).set(u.FACET, facet).set(u.VALUE_KEY, key).set(u.SAID_AT, now)
            .onDuplicateKeyUpdate().set(u.SAID_AT, now)
            .execute()
    }

    fun forget(itemId: String, facet: String, key: String) {
        val u = WORK_FACET_UNKNOWN
        db.deleteFrom(u).where(u.WORK.eq(works.of(ItemKey(source, itemId), Instant.now())), u.FACET.eq(facet), u.VALUE_KEY.eq(key)).execute()
    }

    /** Every word of the [facet] of any work of the source. */
    fun ofFacet(facet: String): List<Unknown> {
        val u = WORK_FACET_UNKNOWN
        return db.select(SITE_WORK.ITEM, u.FACET, u.VALUE_KEY, u.SAID_AT).from(SITE_WORK).join(u).on(u.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), u.FACET.eq(facet))
            .fetch { Unknown(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }
    }
}
