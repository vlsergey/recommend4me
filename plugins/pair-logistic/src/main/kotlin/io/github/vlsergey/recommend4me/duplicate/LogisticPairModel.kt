package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.NoCardRoom
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

    override fun search(
        search: PairSearch,
        models: List<FittedPairModel>,
        card: Cublas?,
        progress: (Double) -> Unit,
    ): List<FoundPair> {
        require(models.size <= MAX_MODELS) { "${models.size} models: a kernel scores $MAX_MODELS at most" }
        val logistic = models.map { m -> m as? Logistic ?: error("a model not of ${ID}'s making") }
        // What a pair needs to be kept, of the models as they are: told to the log, the search not yet led by it
        SearchPlan.of(search, logistic)
        val laid = logistic.map { it.laid(search.families) }
        return card?.let { onCard(search, logistic, laid, it, progress) }
            ?: ProcessorSearch.search(search, laid, Compared(search, progress))
    }

    /**
     * The search on the card: of every work its nearest by a graph; every pair of works of trigrams of other widths.
     * Null when the card fails it: the search is the processor's.
     */
    private fun onCard(
        search: PairSearch,
        logistic: List<Logistic>,
        laid: List<Logistic.Laid>,
        card: Cublas,
        progress: (Double) -> Unit,
    ): List<FoundPair>? {
        val started = System.currentTimeMillis()
        return runCatching {
            graph(search, laid, card)?.let { likeliest ->
                // How much of what a search of every pair keeps it found, and whether the card scored as the
                // processor does
                likeliest.found().also { found -> GraphCheck.tell(search, logistic, likeliest, found) }
            }
                ?: Compared(search, progress).let { compared ->
                    CardSearch.search(search, laid, card, compared = compared).also {
                        compared.told()
                        log.info("pairs searched on the card in {} ms", System.currentTimeMillis() - started)
                    }
                }
        }
            .onFailure { e ->
                log.warn(
                    "the pairs were not searched on the card ({}): in the main memory",
                    e.message ?: e.javaClass.simpleName,
                )
            }
            .getOrNull()
    }

    /** The search by the graph; none when the card has no room for every work at once: the tiles' then. */
    private fun graph(search: PairSearch, laid: List<Logistic.Laid>, card: Cublas): Likeliest? =
        try {
            GraphSearch.search(search, laid, card)
        } catch (_: NoCardRoom) {
            log.info("the graph has no room on the card: every pair searched by tiles")
            null
        }

    override fun fitted(search: PairSearch, models: List<FittedPairModel>) {
        // What the next search will do of the models as they are now
        SearchPlan.of(search, models.mapNotNull { it as? Logistic })
    }

    companion object {
        const val ID = "pair-logistic"

        /** The models a search scores at once: a kernel's registers hold so many. */
        const val MAX_MODELS = 4
    }
}
