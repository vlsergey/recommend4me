package io.github.vlsergey.recommend4me.suggestion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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
 * TWO WALKS OF THE PORTIONS, no work kept between them: the first counts the works of every fold ([BayesFolds]), the
 * second judges every setting on every work by the counts of the other folds and scores what the user answered on by
 * the counts of every other work ([BayesTrials]) — as large as the values and their contexts, whatever the number of
 * works. THE MODEL KEPT IS THE COUNTS ([BayesModel]): a work asked later is scored by them, of its own other values
 * alone, its own pairs taken out. What the user answered on is handed over held out one work at a time so
 * ([FittedSuggester.heldOut]), by the settings chosen.
 */
class NaiveBayesSuggester : FacetSuggester {

    override val id = "naive-bayes-1"

    override fun fit(portions: TaskPortions, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (portions.values.valueCount == 0) return null
        val started = System.currentTimeMillis()
        return BayesFolds.of(portions, stop).takeIf { it.valued }?.let { chosen(portions, it, started, stop) }
    }

    /**
     * The settings tried over the second walk of the [portions] by the counts of the first ([counted]), and the one
     * chosen, fitted.
     */
    private fun chosen(portions: TaskPortions, counted: BayesFolds, started: Long, stop: () -> Boolean): Fitted {
        val side = portions.values
        val trials = BayesTrials(counted, ALPHAS.flatMap { alpha -> SCALES.map { BayesSetting(alpha, it) } })
        trials.walk(portions, stop)
        val tried = trials.settings.indices.toList()
        val chosen =
            oneStandardError(
                tried,
                { trials.held[it] },
                compareByDescending<Int> { trials.settings[it].alpha }.thenBy { trials.settings[it].scale },
            )
        val setting = trials.settings[chosen]
        log.info(
            "{} items × {} values, {} values of other facets: {}; chosen α={} s={} in {} ms",
            portions.items,
            side.valueCount,
            side.contextCount,
            tried.joinToString("; ") {
                val s = trials.settings[it]
                "α=${s.alpha} s=${s.scale}: %.4f ± %.4f".format(trials.held[it].mean, trials.held[it].error)
            },
            setting.alpha,
            setting.scale,
            System.currentTimeMillis() - started,
        )
        val report =
            FitReport(
                settings(setting),
                trials.held[chosen].mean,
                trials.held[chosen].error,
                tried.map {
                    FitTry(
                        settings(trials.settings[it]),
                        trials.held[it].mean,
                        trials.held[it].error,
                        trials.millis(it),
                    )
                },
                fromScratch = true,
            )
        val model = BayesModel(setting.alpha, setting.scale, side.contextKeys, counted.total)
        return Fitted(side.valueKeys, model, report, trials.heldOut(chosen))
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? =
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != FORMAT) return null
            val keys = List(input.readInt()) { input.readUTF() }
            if (keys != task.valueKeys) return null
            val model = BayesModel.read(ByteArray(input.readInt()).also(input::readFully)) ?: return null
            Fitted(keys, model)
        } catch (_: IOException) {
            null
        }

    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? = BayesModel.read(model)?.on(task)

    /** Every value's share among the works the model was fitted on. */
    override fun weights(model: ByteArray): DoubleArray? = BayesModel.read(model)?.shares()

    private fun settings(s: BayesSetting) = mapOf(ALPHA to s.alpha.toString(), SCALE to s.scale.toString())

    /**
     * The [model] fitted — the counts and the settings chosen — of the values [valueKeys]: the state a refit knows,
     * packed whole, the model made of it alone. [heldOut]: of the fitting alone, not packed.
     */
    private class Fitted(
        val valueKeys: List<String>,
        val model: BayesModel,
        override val report: FitReport? = null,
        override val heldOut: HeldOutScores? = null,
    ) : FittedSuggester {
        override fun model(task: SuggestionTask): ByteArray = model.pack()

        override fun pack(): ByteArray {
            val counts = model.pack()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(valueKeys.size)
                valueKeys.forEach(out::writeUTF)
                out.writeInt(counts.size)
                out.write(counts)
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        /** The format of the state packed: the keys of the values and the model. */
        private const val FORMAT = 2
        private const val ALPHA = "α"
        private const val SCALE = "s"

        /** The smoothing tried: a share of a work, one, a few. */
        private val ALPHAS = listOf(0.5, 2.0, 8.0)

        /** The scales of the other values' sum tried: they say much the same of a work many times over. */
        private val SCALES = listOf(0.1, 0.3, 1.0)
    }
}

/** Gives the fitting up once [stop] says so: newer data came, and it is fitted on that. */
internal fun stopIf(stop: () -> Boolean) {
    if (stop()) throw CancellationException("naive Bayes given up for newer changes")
}
