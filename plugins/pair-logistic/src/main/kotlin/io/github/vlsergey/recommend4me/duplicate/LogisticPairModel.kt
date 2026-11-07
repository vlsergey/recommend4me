package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(LogisticPairModel::class.java)

/**
 * THE PAIRS OF WORKS BY A LOGISTIC MODEL OF THEIR LAYERS ([Logistic]), one model a kind, and their search: on the card
 * when the launch has one ([CardSearch]) — every pair of two sites compared once, scored by every model in the same
 * kernel — else in the main memory ([ProcessorSearch]).
 */
class LogisticPairModel : PairModel {
    override val id = ID

    override fun prior(): FittedPairModel = Logistic(null)

    override fun fit(pairs: List<PairLayers>, said: List<Boolean?>): FittedPairModel? = Logistic.fit(pairs, said)

    override fun unpack(bytes: ByteArray): FittedPairModel? = runCatching { Logistic.read(bytes) }.getOrNull()

    override fun search(search: PairSearch, models: List<FittedPairModel>, card: Cublas?): List<FoundPair> {
        require(models.size <= MAX_MODELS) { "${models.size} models: a kernel scores $MAX_MODELS at most" }
        val laid = models.map { m -> (m as? Logistic ?: error("a model not of ${ID}'s making")).laid(search.families) }
        if (card != null) {
            val started = System.currentTimeMillis()
            runCatching {
                return CardSearch.search(search, laid, card).also {
                    log.info("pairs searched on the card in {} ms", System.currentTimeMillis() - started)
                }
            }
                .onFailure { e ->
                    log.warn(
                        "the pairs were not searched on the card ({}): in the main memory",
                        e.message ?: e.javaClass.simpleName,
                    )
                }
        }
        return ProcessorSearch.search(search, laid)
    }

    companion object {
        const val ID = "pair-logistic"

        /** The models a search scores at once: a kernel's registers hold so many. */
        const val MAX_MODELS = 4
    }
}
