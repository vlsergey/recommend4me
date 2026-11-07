package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.ModelState
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE MODELS OF THE PAIRS AS THE PAGE OF THE MODELS SHOWS THEM: the plugin they are fitted by — the one the pairs are
 * looked for by too —, when they were last fitted and how long it took, the answers they learnt from by the word; being
 * fitted while a search or a fitting is planned or running; stale when the plugin in use is not the one that made them.
 */
@Component
class PairModelStates(
    private val plugins: Plugins,
    private val models: PairModels,
    private val answers: PairAnswers,
    private val duplicates: Duplicates,
    private val fitting: PairFitting,
) {
    /** The models of the pairs of the [type]; none of a type of one site, which has no pairs. */
    fun of(type: TypeStore): ModelState? {
        if (type.sources.size < 2) return null
        val search = type.duplicates.search()
        val said = answers.said(type)
        val model = models.model(type.id)
        return ModelState(
            id = ID,
            kind = ModelState.Kind.PAIRS,
            method = model?.id,
            methods = plugins.pairModels.map { it.id },
            fittedAt = search?.fittedAt?.atOffset(ZoneOffset.UTC),
            millis = search?.fitMillis,
            settings = emptyMap(),
            items = said.size,
            answers = said.values.groupingBy { it.name }.eachCount(),
            tried = emptyList(),
            running = duplicates.isSearching(type) || fitting.isFitting(type),
            stale = search != null && search.model != model?.id,
        )
    }

    private companion object {
        const val ID = "pairs"
    }
}
