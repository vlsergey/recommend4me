package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.WholeReadRefused
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_SITE
import java.time.Instant
import org.jooq.DSLContext

/** A fandom of a site: its source and the site's own key of it. */
data class SiteFandomRef(val source: String, val key: String)

/** THE SITES' FANDOMS, every one bound to exactly one normalized fandom, in the user's dictionaries ([db]). */
class FandomSites(private val db: DSLContext) {

    /** Every site's fandom and the normalized fandom it is bound to. */
    fun bindings(): Map<SiteFandomRef, Long> =
        WholeReadRefused.check("every binding of the sites' fandoms")
            .let { _ -> db }
            .select(FANDOM_SITE.SOURCE, FANDOM_SITE.SITE_KEY, FANDOM_SITE.FANDOM)
            .from(FANDOM_SITE)
            .fetch()
            .associate { SiteFandomRef(it.value1()!!, it.value2()!!) to it.value3()!! }

    /**
     * The normalized fandoms the site's fandoms [keys] of the [source] are bound to: those of a few works, by the index
     * of the bindings.
     */
    fun bindingsOf(source: String, keys: Collection<String>): Map<String, Long> =
        keys
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(FANDOM_SITE.SITE_KEY, FANDOM_SITE.FANDOM)
                    .from(FANDOM_SITE)
                    .where(FANDOM_SITE.SOURCE.eq(source), FANDOM_SITE.SITE_KEY.`in`(chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

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

    /** The works of every site's fandom as last counted ([FandomSiteWorks]); none of one not counted yet. */
    fun works(): Map<SiteFandomRef, Int> =
        db.select(FANDOM_SITE.SOURCE, FANDOM_SITE.SITE_KEY, FANDOM_SITE.WORKS)
            .from(FANDOM_SITE)
            .where(FANDOM_SITE.WORKS.isNotNull)
            .fetch()
            .associate { SiteFandomRef(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** The works of the sites' fandoms [counted] kept, by each. */
    fun works(counted: Map<SiteFandomRef, Int>) {
        if (counted.isEmpty()) return
        db.batch(
                counted.map { (site, works) ->
                    db.update(FANDOM_SITE)
                        .set(FANDOM_SITE.WORKS, works)
                        .where(FANDOM_SITE.SOURCE.eq(site.source), FANDOM_SITE.SITE_KEY.eq(site.key))
                }
            )
            .execute()
    }

    /** [site] bound to no normalized fandom any more. */
    fun unbind(site: SiteFandomRef) {
        db.deleteFrom(FANDOM_SITE)
            .where(FANDOM_SITE.SOURCE.eq(site.source), FANDOM_SITE.SITE_KEY.eq(site.key))
            .execute()
    }

    private companion object {
        /** The widest key of a site's fandom kept: the width of its column. */
        const val KEY_WIDTH = 500
    }
}

/** The keys asked by one query: the length of its list, not a meaning. */
private const val BATCH = 1000
