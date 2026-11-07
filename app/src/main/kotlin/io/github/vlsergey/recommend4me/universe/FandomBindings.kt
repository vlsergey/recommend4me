package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_FANDOM
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE USER'S BINDINGS OF THE SITES' FANDOMS TO THE UNIVERSES OF THE DICTIONARY, in the dictionaries: a work of a bound
 * fandom is drafted with its universes.
 */
class FandomBindings internal constructor(private val db: DSLContext) {

    /**
     * The universes the user bound the fandoms of the [source] to: the site's key of a fandom to the universes' values.
     */
    fun universesOf(source: String): Map<String, Set<String>> =
        db.select(SITE_FANDOM.FANDOM, SITE_FANDOM.CATALOGUE, SITE_FANDOM.UNIVERSE_ID)
            .from(SITE_FANDOM)
            .where(SITE_FANDOM.SOURCE.eq(source))
            .fetch()
            .groupBy({ it.value1()!! }, { UniverseFacet.valueOf(it.value2()!!, it.value3()!!) })
            .mapValues { it.value.toSet() }

    /**
     * The [fandom] of the [source] bound to the [universe], or the binding taken back; false when the universe is not
     * in the dictionary.
     */
    fun bind(source: String, fandom: String, universe: UniverseKey, bound: Boolean, now: Instant): Boolean {
        val known =
            db.fetchExists(UNIVERSE, UNIVERSE.CATALOGUE.eq(universe.catalogue), UNIVERSE.UNIVERSE_ID.eq(universe.id))
        if (known) {
            val row =
                DSL.and(
                    SITE_FANDOM.SOURCE.eq(source),
                    SITE_FANDOM.FANDOM.eq(fandom),
                    SITE_FANDOM.CATALOGUE.eq(universe.catalogue),
                    SITE_FANDOM.UNIVERSE_ID.eq(universe.id),
                )
            if (!bound) db.deleteFrom(SITE_FANDOM).where(row).execute()
            else if (!db.fetchExists(SITE_FANDOM, row)) {
                db.insertInto(SITE_FANDOM)
                    .set(SITE_FANDOM.SOURCE, source)
                    .set(SITE_FANDOM.FANDOM, fandom)
                    .set(SITE_FANDOM.CATALOGUE, universe.catalogue)
                    .set(SITE_FANDOM.UNIVERSE_ID, universe.id)
                    .set(SITE_FANDOM.BOUND_AT, now)
                    .execute()
            }
        }
        return known
    }
}
