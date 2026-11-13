package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.database.model.tables.references.SEARCH_WEIGHT
import java.time.Instant
import org.jooq.DSLContext

/** The weights learnt of the order of what a search finds ([SearchWeights]), in the model database of the type. */
class SearchWeightRepository(private val db: DSLContext) {

    /** The weights kept, and when they were learnt; null when none is. */
    fun read(): Pair<SearchWeights, Instant>? {
        val rows = db.selectFrom(SEARCH_WEIGHT).fetch()
        if (rows.isEmpty()) return null
        val weights = rows.associate { it.feature!! to FeatureWeight(it.weight!!, it.mean!!, it.scale!!) }
        return SearchWeights(weights) to rows.maxOf { it.fittedAt!! }
    }

    /** The [weights] learnt now kept in place of those before; none kept of null. */
    fun keep(weights: SearchWeights?, now: Instant) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(SEARCH_WEIGHT).execute()
        weights?.features?.forEach { (feature, f) ->
            tx.insertInto(SEARCH_WEIGHT)
                .set(SEARCH_WEIGHT.FEATURE, feature)
                .set(SEARCH_WEIGHT.WEIGHT, f.weight)
                .set(SEARCH_WEIGHT.MEAN, f.mean)
                .set(SEARCH_WEIGHT.SCALE, f.scale)
                .set(SEARCH_WEIGHT.FITTED_AT, now)
                .execute()
        }
    }
}
