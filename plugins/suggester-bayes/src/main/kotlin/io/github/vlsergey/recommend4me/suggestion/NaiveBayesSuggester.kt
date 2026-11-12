package io.github.vlsergey.recommend4me.suggestion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(NaiveBayesSuggester::class.java)

/**
 * NAIVE BAYES OVER THE WORK'S OTHER VALUES: the chance a work has a value from how often the works with each of its
 * other values — its fandom, the site's tags, its author — have it, each told apart as if alone, with the value's own
 * share among the works ([BayesCounts.chances]). Reads no text and no picture: fast, and a measure of how much of a
 * facet the other facets tell by themselves.
 *
 * N₁ᵥ, N₀ᵥ: the weights of the pairs of v labelled 1 and 0 ([SuggestionPairs]); n₁(c,v), n₀(c,v) of the works with c;
 * q_c the share of the works with c. The values of the other facets are not independent — a fandom and its pairings say
 * the same twice — so their sum is scaled by s; α smooths a rare one. Both chosen by the held-out loss of the five
 * folds, the one-standard-error rule preferring the stronger smoothing and the smaller scale.
 *
 * THE MODEL KEPT IS THE COUNTS OF THE WORKS FITTED ON ([BayesModel]): a work asked later is scored by them, of its own
 * other values alone — its own pairs taken out when it was counted.
 */
class NaiveBayesSuggester : FacetSuggester {

    override val id = "naive-bayes-1"

    override fun fit(task: SuggestionTask, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val pairs = SuggestionPairs(task)
        val works = BayesWork.of(task, pairs)
        val tried = ArrayList<Tried>()
        for (alpha in ALPHAS) for (scale in SCALES) {
            if (stop()) throw CancellationException("naive Bayes given up for newer changes")
            val at = System.currentTimeMillis()
            val held = pairs.heldOut { fold, items ->
                val train = BayesCounts.of(task.valueCount, task.contextCount, works) { pairs.foldOf[it] != fold }
                items.map { i ->
                    train.chances(task.context[i].distinct().sorted().toIntArray(), null, alpha, scale)
                }
            }
            tried += Tried(alpha, scale, held, System.currentTimeMillis() - at)
        }
        val chosen = oneStandardError(tried, { it.held }, compareByDescending<Tried> { it.alpha }.thenBy { it.scale })
        log.info(
            "{} items × {} values, {} values of other facets: {}; chosen α={} s={} in {} ms",
            task.itemCount,
            task.valueCount,
            task.contextCount,
            tried.joinToString("; ") { "α=${it.alpha} s=${it.scale}: %.4f ± %.4f".format(it.held.mean, it.held.error) },
            chosen.alpha,
            chosen.scale,
            System.currentTimeMillis() - started,
        )
        val report =
            FitReport(
                settings(chosen),
                chosen.held.mean,
                chosen.held.error,
                tried.map { FitTry(settings(it), it.held.mean, it.held.error, it.millis) },
                fromScratch = true,
            )
        return Fitted(chosen.alpha, chosen.scale, report)
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != FORMAT) return null
        return Fitted(input.readDouble(), input.readDouble())
    }

    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? = BayesModel.read(model)?.on(task)

    /** Every value's share among the works the model was fitted on. */
    override fun weights(model: ByteArray): DoubleArray? = BayesModel.read(model)?.shares()

    private class Tried(val alpha: Double, val scale: Double, val held: HeldLoss, val millis: Long)

    private fun settings(t: Tried) = mapOf(ALPHA to t.alpha.toString(), SCALE to t.scale.toString())

    /** The settings chosen: the state a refit knows; the counts of the works are the model ([model]). */
    private class Fitted(val alpha: Double, val scale: Double, override val report: FitReport? = null) :
        FittedSuggester {
        override fun model(task: SuggestionTask): ByteArray = BayesModel.of(task, alpha, scale).pack()

        override fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeDouble(alpha)
                out.writeDouble(scale)
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        private const val FORMAT = 1
        private const val ALPHA = "α"
        private const val SCALE = "s"

        /** The smoothing tried: a share of a work, one, a few. */
        private val ALPHAS = listOf(0.5, 2.0, 8.0)

        /** The scales of the other values' sum tried: they say much the same of a work many times over. */
        private val SCALES = listOf(0.1, 0.3, 1.0)
    }
}
