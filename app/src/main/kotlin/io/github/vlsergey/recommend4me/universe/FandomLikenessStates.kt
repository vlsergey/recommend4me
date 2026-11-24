package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.ModelQuality
import io.github.vlsergey.recommend4me.api.model.ModelState
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE LIKENESS OF THE FANDOMS' NAMES AS THE PAGE OF THE MODELS SHOWS IT, on the page of the type that keeps the
 * universes: the way a site's fandom is read for the universes alike one, chosen among the ways; when it was learnt, of
 * how many universes, how right it is on the held out beside the encoder's own; the changes of no word of the user's
 * counted since, and how many learn it again by themselves.
 */
@Component
class FandomLikenessStates(
    private val stores: Stores,
    private val learning: FandomLikenessLearning,
    private val ways: FandomWays,
) {
    /** The type that keeps the universes, of the [type] given; null of another. */
    fun keeper(type: String): TypeStore? = stores.withUniverses().firstOrNull()?.takeIf { it.type.id == type }

    /** The likeness of the fandoms' names, of the [type] that keeps the universes; none of another. */
    fun of(type: TypeStore): ModelState? {
        if (keeper(type.type.id) == null) return null
        val kept = type.fandomLikeness.kept()
        return ModelState(
            id = ID,
            kind = ModelState.Kind.FANDOMS,
            method = ways.chosen().name,
            methods = FandomWay.entries.map { it.name },
            fittedAt = kept?.fittedAt?.atOffset(ZoneOffset.UTC),
            quality = kept?.fit?.heldOut?.let { ModelQuality(ModelQuality.Measure.CONCORDANCE, it) },
            baseline = kept?.fit?.asEncoded?.let { ModelQuality(ModelQuality.Measure.CONCORDANCE, it) },
            settings = emptyMap(),
            items = kept?.fit?.universes,
            tried = emptyList(),
            running = learning.learning(),
            stale = kept != null && kept.autoChanges > 0,
            autoChanges = kept?.autoChanges,
            autoChangesDue = kept?.let { FandomLikenessLearning.due(it.fit.universes) },
        )
    }

    private companion object {
        const val ID = "fandoms"
    }
}
