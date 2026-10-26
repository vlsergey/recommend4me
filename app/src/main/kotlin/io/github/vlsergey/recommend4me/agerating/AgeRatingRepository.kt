package io.github.vlsergey.recommend4me.agerating

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.AGE_RATING_WEIGHT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** The model of the age rating kept in the dictionaries: every feature's weight a row, the thresholds the rows "threshold:<k>". */
class AgeRatingRepository(private val db: DSLContext) {

    fun read(): AgeRatingModel? {
        val rows = db.selectFrom(AGE_RATING_WEIGHT).fetch()
        val thresholds = rows.filter { it.feature!!.startsWith(THRESHOLD) }.sortedBy { it.feature!!.removePrefix(THRESHOLD).toInt() }.map { it.weight!! }
        if (thresholds.isEmpty()) return null
        return AgeRatingModel(rows.filter { !it.feature!!.startsWith(THRESHOLD) }.associate { it.feature!! to it.weight!! }, thresholds.toDoubleArray())
    }

    /** The [model] kept in place of the last — none when it is null — with how many works it learnt from, of every feature and in all ([total]). */
    fun keep(model: AgeRatingModel?, works: Map<String, Int>, total: Int, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(AGE_RATING_WEIGHT).execute()
        if (model == null) return@transaction
        val rows = model.thresholds.withIndex().map { (k, t) -> Triple("$THRESHOLD$k", t, total) } +
            model.weights.map { (feature, w) -> Triple(feature, w, works[feature] ?: 0) }
        rows.chunked(BATCH).forEach { chunk ->
            val insert = tx.insertInto(AGE_RATING_WEIGHT, AGE_RATING_WEIGHT.FEATURE, AGE_RATING_WEIGHT.WEIGHT, AGE_RATING_WEIGHT.WORKS, AGE_RATING_WEIGHT.FITTED_AT)
            chunk.forEach { (feature, w, n) -> insert.values(feature.take(200), w, n, now) }
            insert.execute()
        }
    }

    companion object {
        private const val THRESHOLD = "threshold:"
        private const val BATCH = 1000

        fun tag(id: Long) = "tag:$id"
        fun source(id: String) = "source:$id"
    }
}
