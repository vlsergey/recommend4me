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
    ): WorksRead {
        if (ids == null) refuseTooLarge(store)
        return WorksRead(
            store,
            encoder,
            plugins.imageEncoder()?.id,
            stores.typeOf(store.id).embeddings.ids(),
            ids,
            characters,
            every?.takeIf { ids == null },
            if (card && ids == null) cards.getIfAvailable()?.cublas() else null,
        )
    }

    /**
     * A pass over every work of the [store] refused when it would not fit the memory, every work held at once
     * ([TooManyWorks]): the work asking for it fails, not the application out of its memory (ficbook's 1.39 million
     * works, 2026-11-17) — till such a pass reads its works a portion at a time.
     */
    private fun refuseTooLarge(store: SourceStore) {
        val works = store.items.count()
        val most = Runtime.getRuntime().maxMemory() / BYTES_A_WORK
        if (works > most) throw TooManyWorks(store.id, works, most)
    }

    /** The characters of the dictionary of the [store]'s type, for one operation to read once ([CharactersRead]). */
    fun charactersRead(store: SourceStore) = CharactersRead(stores.typeOf(store.id).universes)
}

/** A pass over every work of a source refused: more works than the memory holds of a pass ([WorkPasses]). */
class TooManyWorks(source: String, works: Int, most: Long) :
    IllegalStateException("$source: a pass over its $works works refused, the memory holds $most")

/**
 * The memory a pass over a source's works holds of a work, twice as much as it was seen to (the memory of 12 GB full of
 * a pass over a million works of ficbook): what tells a source too large for a pass ([WorkPasses]).
 */
private const val BYTES_A_WORK = 24L * 1024
