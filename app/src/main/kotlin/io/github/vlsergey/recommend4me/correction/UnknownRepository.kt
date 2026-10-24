package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.FACET_UNKNOWN
import org.jooq.DSLContext
import java.time.Instant

/** The user's word that whether the work [itemId] has the value [key] of the [facet] is not clear of it. */
data class Unknown(val itemId: String, val facet: String, val key: String, val saidAt: Instant)

/**
 * The user's word, of the works of one source, that whether a work has a value is not clear of it —
 * kept with the corrections, apart from them: the value is asked of the work no more, and nothing
 * learns of it; the work's values stay as they were.
 */
class UnknownRepository(private val db: DSLContext) {

    fun say(itemId: String, facet: String, key: String, now: Instant) {
        db.insertInto(FACET_UNKNOWN).set(FACET_UNKNOWN.ITEM_ID, itemId).set(FACET_UNKNOWN.FACET, facet).set(FACET_UNKNOWN.VALUE_KEY, key).set(FACET_UNKNOWN.SAID_AT, now)
            .onDuplicateKeyUpdate().set(FACET_UNKNOWN.SAID_AT, now)
            .execute()
    }

    fun forget(itemId: String, facet: String, key: String) {
        db.deleteFrom(FACET_UNKNOWN).where(FACET_UNKNOWN.ITEM_ID.eq(itemId), FACET_UNKNOWN.FACET.eq(facet), FACET_UNKNOWN.VALUE_KEY.eq(key)).execute()
    }

    /** Every word of the [facet] of any work. */
    fun ofFacet(facet: String): List<Unknown> =
        db.selectFrom(FACET_UNKNOWN).where(FACET_UNKNOWN.FACET.eq(facet)).fetch { Unknown(it.itemId!!, it.facet!!, it.valueKey!!, it.saidAt!!) }

    /** The words of the work's facets, removed with it. */
    fun forgetItem(itemId: String) {
        db.deleteFrom(FACET_UNKNOWN).where(FACET_UNKNOWN.ITEM_ID.eq(itemId)).execute()
    }
}
