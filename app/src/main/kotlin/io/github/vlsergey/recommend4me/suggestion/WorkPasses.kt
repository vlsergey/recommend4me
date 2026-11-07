package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.CharactersRead
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * A PASS OVER THE WORKS OF A SOURCE begun: what every facet of it reads alike ([WorksRead]) — the dictionary's
 * characters among it — and the card its inputs are put on.
 */
@Component
internal class WorkPasses(
    private val stores: Stores,
    private val plugins: Plugins,
    /**
     * The card, when the launch asks for it: the inputs of a pass of every work put there for its facets ([WorksRead]).
     */
    private val cards: ObjectProvider<CardSource>,
) {
    /**
     * What the facets of a pass over the works [ids] (every work when null) read alike. [card]: the pass ends with the
     * operation, and whatever reads its inputs ends with it — of every work its inputs put on the card for its facets,
     * freed as it is closed; never of a task handed out.
     */
    fun worksRead(
        store: SourceStore,
        encoder: TextEncoder,
        ids: Collection<String>?,
        every: EveryItemFacets? = null,
        characters: CharactersRead = charactersRead(store),
        card: Boolean = false,
    ) =
        WorksRead(
            store,
            encoder,
            plugins.imageEncoder()?.id,
            stores.typeOf(store.id).embeddings.ids(),
            ids,
            characters,
            every?.takeIf { ids == null },
            if (card && ids == null) cards.getIfAvailable()?.cublas() else null,
        )

    /** The characters of the dictionary of the [store]'s type, for one operation to read once ([CharactersRead]). */
    fun charactersRead(store: SourceStore) = CharactersRead(stores.typeOf(store.id).universes)
}
