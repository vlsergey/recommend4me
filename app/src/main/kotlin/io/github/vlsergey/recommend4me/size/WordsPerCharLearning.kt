package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.NoCardRoom
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(WordsPerCharLearning::class.java)

/**
 * THE MODEL OF HOW MANY WORDS A SITE ITEM HAS TO A CHARACTER ([WordsPerChar]) FITTED AND KEPT, its weights alone
 * ([KeptRatios]): what [WorkSizes] works the size a site does not state out of. Learnt of the items whose words and
 * characters are both known ([SizeExamples]): their kept texts, a site that states both, and the items of one work
 * where one site states the words and another the characters, each trusted as the chance it is of the same text
 * ([SizeTrust]); its penalties and weights worked out of the data ([SizeVariances]); on the card when there is one with
 * room, else on the processor. When it is fitted again is [WordsPerCharFitting]'s.
 */
@Service
class WordsPerCharLearning(private val indexes: ListIndexes, private val cards: ObjectProvider<CardSource>) {

    /** The kernels of the ridge and the card they were compiled for: compiled once a card. */
    @Volatile private var compiled: Pair<Cublas, CudaKernels>? = null

    /**
     * The model of the type fitted of every example and kept: the ridge solved on the card when there is one with room
     * for the examples, else on the processor (owner, 2026-11-20: "on the GPU at once").
     */
    fun fit(type: TypeStore) {
        val laps = Laps()
        val features = SizeFeatures()
        val examples = SizeExamples.read(type, indexes.of(type.id), features)
        laps.lap("read")
        val sure = examples.own
        if (sure.isEmpty()) return
        val names = features.names
        log.info(
            "{}: the words to a character to learn of {} items of their own numbers and {} of two sites, {} features",
            type.id,
            sure.size,
            examples.across.size,
            names.size,
        )
        val kinds = WordsPerChar.kindsOf(names)
        val rows = SizeRows(sure, names.size)
        val (crossed, where) =
            solved(rows) { ridge ->
                val fitted = SizeVariances(rows, kinds, ridge).fit()
                val weights = fitted.weights()
                Crossed(fitted, ridge.outOfFold(weights, fitted.lambda, fitted.x), ridge.meanOutOfFold(weights))
            }
        laps.lap("fitted and cross-validated on $where")
        val across = examples.across
        val (fitted, trust) = withTwoSites(crossed.fitted, sure, across, names.size, kinds)
        val model = WordsPerChar(fitted.x, fitted.sample)
        laps.lap("fitted with two sites' on $where")
        val weights = model.named(names)
        type.sizes.keep(weights)
        laps.lap("kept")
        val counts = IntArray(names.size).also { c -> sure.forEach { e -> e.features.forEach { c[it]++ } } }
        log.info(
            "{}: the steps of the words to a character — {}",
            type.id,
            SizeSteps.told(model.weights, names, counts),
        )
        told(type, sure.size, across.size, trust.sum(), weights.size, crossed, fitted, laps)
    }

    /** The model fitted told in the log: of how much, how right, the variances worked out, the laps. */
    private fun told(
        type: TypeStore,
        own: Int,
        across: Int,
        trusted: Double,
        features: Int,
        crossed: Crossed,
        fitted: SizeVariances.Fitted,
        laps: Laps,
    ) {
        log.info(
            "{}: the words to a character learnt from {} items of their own numbers and {} of two sites trusted {} " +
                "in all, {} features, out of fold {} by a logarithm, the mean alone {}; a work's own deviation {}, " +
                "its count's of a character {} — its own text weighing as the model's at {} characters —, the steps' " +
                "spread {}: {}",
            type.id,
            own,
            across,
            "%.1f".format(trusted),
            features,
            "%.4f".format(crossed.error),
            "%.4f".format(crossed.meanError),
            "%.4f".format(Math.sqrt(fitted.noise)),
            "%.4f".format(Math.sqrt(fitted.counting)),
            "%.0f".format(fitted.sample),
            WordsPerChar.KINDS.indices.joinToString {
                "${WordsPerChar.KINDS[it]} %.4f".format(Math.sqrt(fitted.tau[it]))
            },
            laps,
        )
    }

    /**
     * Of the model fitted of the examples of their own numbers ([own], [sure]), every example of two sites ([across])
     * trusted as the chance it is of the same text, of its residual from it, and the model fitted again of them all —
     * begun at it —; and the trust.
     */
    private fun withTwoSites(
        own: SizeVariances.Fitted,
        sure: List<SizeExample>,
        across: List<SizeExample>,
        features: Int,
        kinds: IntArray,
    ): Pair<SizeVariances.Fitted, DoubleArray> {
        if (across.isEmpty()) return own to DoubleArray(0)
        val first = WordsPerChar(own.x, own.sample)
        val trust =
            SizeTrust.of(
                DoubleArray(across.size) { across[it].logRatio - first.log(across[it].features) },
                DoubleArray(across.size) { own.variance(across[it].chars) },
            )
        val all = SizeRows(sure + across, features)
        val trusted = DoubleArray(all.count) { if (it < sure.size) 1.0 else trust[it - sure.size] }
        return solved(all) { ridge -> SizeVariances(all, kinds, ridge).fit(trusted, own) }.first to trust
    }

    /** The model fitted of every example, and the errors out of fold of it and of the mean alone. */
    private class Crossed(val fitted: SizeVariances.Fitted, val error: Double, val meanError: Double)

    /**
     * [body] of the ridge of [rows] on the card when there is one with room for them — its memory had in a block of
     * work —, else on the processor; and where.
     */
    private fun <T> solved(rows: SizeRows, body: (SizeRidge) -> T): Pair<T, String> {
        card()?.let { (cublas, kernels) ->
            val bytes = SizeGram.OnCard.bytes(rows)
            try {
                return cublas.withMemory(bytes, bytes) { memory ->
                    body(SizeRidge(rows, SizeGram.OnCard(rows, kernels, memory)))
                } to "the card"
            } catch (e: NoCardRoom) {
                log.info("the words to a character fitted on the processor: {}", e.message)
            }
        }
        return body(SizeRidge(rows, SizeGram.OnProcessor(rows))) to "the processor"
    }

    /** The card with the kernels of the ridge; none without a card, or of kernels that would not compile. */
    private fun card(): Pair<Cublas, CudaKernels>? {
        val cublas = cards.getIfAvailable()?.cublas() ?: return null
        return compiled?.takeIf { it.first === cublas }
            ?: SizeGram.OnCard.kernels(cublas)?.let { cublas to it }?.also { compiled = it }
    }
}
