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
            "{}: how the sites count the characters, of our texts' classes — {}",
            type.id,
            examples.rules.entries.joinToString("; ") { (source, rule) -> "$source: $rule" },
        )
        log.info(
            "{}: the words to a character to learn of {} items of their own numbers and {} of two sites, {} features",
            type.id,
            sure.size,
            examples.across.size,
            names.size,
        )
        val rows = SizeRows(sure, names.size)
        val begun = Variances.kept(type.sizes.kept(Variances.PREFIX))
        val (crossed, where) =
            solved(rows) { ridge ->
                val variances = SizeVariances(rows, WordsPerChar.kindsOf(names), ridge)
                val fitted = variances.fit(begun)
                Crossed(
                    fitted,
                    ridge.meanHeld(fitted.weights()),
                    "${ridge.solutions} solutions — ${variances.choosing} of them choosing the penalties in " +
                        "${variances.rounds} rounds — of ${ridge.steps} steps, solving ${ridge.nanos / NANOS} ms",
                )
            }
        laps.lap("fitted on $where${if (begun == null) " begun anew" else ""}")
        val across = examples.across
        val (fitted, trust) = withTwoSites(crossed.fitted, sure, across, names.size)
        val model = WordsPerChar(fitted.x, fitted.variances.sample)
        laps.lap("solved with two sites'")
        val weights =
            model.named(names) +
                fitted.variances.named() +
                examples.rules.flatMap { (source, rule) -> rule.named(source).entries }.associate { it.toPair() }
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
                "in all, {} features, on a tenth held out {} by a logarithm, the mean alone {}; a work's own " +
                "deviation {}, its count's of a character {} — its own text weighing as the model's at {} " +
                "characters —, the steps' spread {}; {}: {}",
            type.id,
            own,
            across,
            "%.1f".format(trusted),
            features,
            "%.4f".format(crossed.fitted.held),
            "%.4f".format(crossed.meanError),
            "%.4f".format(Math.sqrt(fitted.variances.noise)),
            "%.4f".format(Math.sqrt(fitted.variances.counting)),
            "%.0f".format(fitted.variances.sample),
            WordsPerChar.KINDS.indices.joinToString {
                "${WordsPerChar.KINDS[it]} %.4f".format(Math.sqrt(fitted.variances.tau[it]))
            },
            crossed.cost,
            laps,
        )
    }

    /**
     * Of the model fitted of the examples of their own numbers ([own], [sure]), every example of two sites ([across])
     * trusted as the chance it is of the same text, of its residual from it, and the steps solved again of them all —
     * begun at it, of its variances: two thousand examples among a million choose no variance anew —; and the trust.
     */
    private fun withTwoSites(
        own: SizeVariances.Fitted,
        sure: List<SizeExample>,
        across: List<SizeExample>,
        features: Int,
    ): Pair<SizeVariances.Fitted, DoubleArray> {
        if (across.isEmpty()) return own to DoubleArray(0)
        val first = WordsPerChar(own.x, own.variances.sample)
        val trust =
            SizeTrust.of(
                DoubleArray(across.size) { across[it].logRatio - first.log(across[it].features) },
                DoubleArray(across.size) { own.variances.variance(across[it].chars) },
            )
        val all = SizeRows(sure + across, features)
        val trusted = DoubleArray(all.count) { if (it < sure.size) 1.0 else trust[it - sure.size] }
        val x = solved(all) { ridge -> ridge.solve(own.variances.weights(all.chars, trusted), own.lambda, own.x) }.first
        return SizeVariances.Fitted(x, own.lambda, own.variances, trusted, all.chars, own.held) to trust
    }

    /** The model fitted of every example, the error of the mean alone on the tenth held out, and what it cost. */
    private class Crossed(val fitted: SizeVariances.Fitted, val meanError: Double, val cost: String)

    /**
     * [body] of the ridge of [rows] on the card when there is one with room for them — its memory had in a block of
     * work —, else on the processor; and where.
     */
    private fun <T> solved(rows: SizeRows, body: (SizeRidge) -> T): Pair<T, String> {
        card()?.let { (cublas, kernels) ->
            val bytes = SizeOnCard.bytes(rows)
            try {
                return cublas.withMemory(bytes, bytes) { memory ->
                    body(SizeRidge(rows, SizeOnCard(rows, kernels, memory)))
                } to "the card"
            } catch (e: NoCardRoom) {
                log.info("the words to a character fitted on the processor: {}", e.message)
            }
        }
        return body(SizeRidge(rows, SizeOnProcessor(rows))) to "the processor"
    }

    /** The card with the kernels of the ridge; none without a card, or of kernels that would not compile. */
    private fun card(): Pair<Cublas, CudaKernels>? {
        val cublas = cards.getIfAvailable()?.cublas() ?: return null
        return compiled?.takeIf { it.first === cublas }
            ?: SizeOnCard.kernels(cublas)?.let { cublas to it }?.also { compiled = it }
    }

    private companion object {
        /** Nanoseconds in a millisecond. */
        const val NANOS = 1_000_000
    }
}
