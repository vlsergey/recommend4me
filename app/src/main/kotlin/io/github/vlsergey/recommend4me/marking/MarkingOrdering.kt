package io.github.vlsergey.recommend4me.marking

import io.github.vlsergey.recommend4me.api.model.MarkVerdict
import io.github.vlsergey.recommend4me.api.model.MarkingOrder
import io.github.vlsergey.recommend4me.source.SourceStore
import kotlin.math.abs
import kotlin.math.ln

/** A work of the table of the marking: where it is, its chance, the user's word on it. */
internal class MarkingRow(val store: SourceStore, val id: String, val chance: Double?, val answer: MarkVerdict?)

/**
 * The order the works of the marking are asked in ([MarkingOrder]): the likeliest first; the disputed — those nearest
 * to where the user's answers change — first; or the answers the model believes least first.
 */
internal object MarkingOrdering {

    /** The least chance [logit] takes: a nought or a one would lie at no finite place. */
    private const val LEAST_CHANCE = 1e-12

    /** The [rows] in the [order]; [judged], the works answered "has it" or "not", tell where the disputed are. */
    fun sorted(rows: List<MarkingRow>, judged: List<MarkingRow>, order: MarkingOrder): List<MarkingRow> {
        val likeliest = compareByDescending<MarkingRow> { it.chance ?: -1.0 }.thenBy { it.store.id }.thenBy { it.id }
        return when (order) {
            MarkingOrder.LIKELY -> rows.sortedWith(likeliest)
            MarkingOrder.DISPUTED -> {
                val boundary = boundary(judged)
                rows.sortedWith(
                    compareBy<MarkingRow> { r -> r.chance?.let { abs(logit(it) - boundary) } ?: Double.MAX_VALUE }
                        .then(likeliest)
                )
            }
            MarkingOrder.CONTRADICTORY ->
                rows.sortedWith(
                    compareByDescending<MarkingRow> { r ->
                            r.chance?.let { c -> r.answer?.let { disbelief(c, it) } } ?: -1.0
                        }
                        .then(likeliest)
                )
        }
    }

    /** The chance on the scale a logistic model adds up on. */
    private fun logit(chance: Double): Double =
        chance.coerceIn(LEAST_CHANCE, 1 - LEAST_CHANCE).let { ln(it / (1 - it)) }

    /**
     * Where on the scale of [logit] the user's answers on the value change: between the mean of the works answered "has
     * it" and of those answered "not". Nearly every work a model learns from is one not answered, so that even a work
     * worth asking about may have a chance of some percent: what is in doubt is told by the answers, not by a half — a
     * half while either kind is missing.
     */
    private fun boundary(judged: List<MarkingRow>): Double {
        val has = judged.filter { it.answer == MarkVerdict.YES }.mapNotNull { it.chance?.let(::logit) }
        val not = judged.filter { it.answer == MarkVerdict.NO }.mapNotNull { it.chance?.let(::logit) }
        return if (has.isEmpty() || not.isEmpty()) 0.0 else (has.average() + not.average()) / 2
    }

    /**
     * How little the model believes the user's [answer]: of "has it" the chance the work has it not, of "not" the
     * chance it has; of "unclear", none.
     */
    private fun disbelief(chance: Double, answer: MarkVerdict): Double? =
        when (answer) {
            MarkVerdict.YES -> 1 - chance
            MarkVerdict.NO -> chance
            MarkVerdict.UNKNOWN -> null
        }
}
