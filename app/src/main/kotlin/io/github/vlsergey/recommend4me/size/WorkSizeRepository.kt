package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_SIZE_WEIGHT
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The model of how many words a site item has to a character, in the model database of its content type: its weight of
 * every feature by the feature's name ([KeptRatios]), made again by [WordsPerCharFitting].
 */
class WorkSizeRepository(private val db: DSLContext) {

    /** The model kept: of none fitted, no weight. */
    fun model(): KeptRatios =
        KeptRatios(
            db.select(WORK_SIZE_WEIGHT.FEATURE, WORK_SIZE_WEIGHT.WEIGHT)
                .from(WORK_SIZE_WEIGHT)
                .fetchMap({ it.value1()!! }, { it.value2()!! })
        )

    /** The [weights] of the model fitted, in place of the last. */
    fun keep(weights: Map<String, Double>) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(WORK_SIZE_WEIGHT).execute()
        weights.entries.chunked(BATCH).forEach { chunk ->
            val insert = tx.insertInto(WORK_SIZE_WEIGHT, WORK_SIZE_WEIGHT.FEATURE, WORK_SIZE_WEIGHT.WEIGHT)
            chunk.forEach { (feature, weight) -> insert.values(feature, weight) }
            insert.execute()
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
