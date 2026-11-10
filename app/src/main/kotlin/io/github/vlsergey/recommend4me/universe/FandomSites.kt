package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_SITE
import java.time.Instant
import org.jooq.DSLContext

/** A fandom of a site: its source and the site's own key of it. */
data class SiteFandomRef(val source: String, val key: String)

/** THE SITES' FANDOMS, every one bound to exactly one normalized fandom, in the user's dictionaries ([db]). */
class FandomSites(private val db: DSLContext) {

    /** Every site's fandom and the normalized fandom it is bound to. */
    fun bindings(): Map<SiteFandomRef, Long> =
        db.select(FANDOM_SITE.SOURCE, FANDOM_SITE.SITE_KEY, FANDOM_SITE.FANDOM).from(FANDOM_SITE).fetch().associate {
            SiteFandomRef(it.value1()!!, it.value2()!!) to it.value3()!!
        }

    /** The normalized fandom [site] is bound to; null of one not bound yet. */
    fun fandomOf(site: SiteFandomRef): Long? =
        db.select(FANDOM_SITE.FANDOM)
            .from(FANDOM_SITE)
            .where(FANDOM_SITE.SOURCE.eq(site.source), FANDOM_SITE.SITE_KEY.eq(site.key))
            .fetchOne()
            ?.value1()

    /** [site] bound to the normalized fandom [id], whatever it was bound to before. */
    fun bind(site: SiteFandomRef, id: Long, now: Instant) {
        db.insertInto(FANDOM_SITE)
            .set(FANDOM_SITE.SOURCE, site.source)
            .set(FANDOM_SITE.SITE_KEY, site.key.take(KEY_WIDTH))
            .set(FANDOM_SITE.FANDOM, id)
            .set(FANDOM_SITE.BOUND_AT, now)
            .onDuplicateKeyUpdate()
            .set(FANDOM_SITE.FANDOM, id)
            .set(FANDOM_SITE.BOUND_AT, now)
            .execute()
    }

    private companion object {
        /** The widest key of a site's fandom kept: the width of its column. */
        const val KEY_WIDTH = 500
    }
}
