package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.CharactersRead
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * A PASS OVER THE WORKS OF A SOURCE, A PORTION AT A TIME: what every facet of a portion reads alike ([WorksRead]) — the
 * dictionary's characters among it — and the card its inputs are put on. NO PASS HOLDS MORE THAN [most] WORKS (user,
 * 2026-11-17): of ficbook's 1.39 million works a pass holding every work filled the memory of 12 GB; every work of a
 * source is read [forEachPortion], [most] of them at a time, in the order of their ids.
 */
@Component
internal class WorkPasses(
    private val stores: Stores,
    private val plugins: Plugins,
    /** The card, when the launch asks for it: the inputs of a portion put there for its facets ([WorksRead]). */
    private val cards: ObjectProvider<CardSource>,
    /**
     * The most works a portion holds: [TaskPortions.MOST], fewer of a test that cuts a source of a few works into
     * several portions.
     */
    @Value("\${recommend4me.portion-works:${TaskPortions.MOST}}") val most: Int,
) {
    init {
        require(most in 1..TaskPortions.MOST) { "A portion of $most works" }
    }

    /**
     * What the facets of the works [ids] read alike — [TaskPortions.MOST] of them at most, else [TooManyWorks]. [card]:
     * their inputs put on the card for their facets, freed as it is closed; never of a task handed out. [inputs]: false
     * — their vectors not read, of a reading of their values alone. [known]: their site's values and the user's answers
     * read already by the reading this serves.
     */
    fun worksRead(
        store: SourceStore,
        encoder: TextEncoder,
        ids: Collection<String>,
        characters: CharactersRead = charactersRead(store),
        card: Boolean = false,
        inputs: Boolean = true,
        known: EveryItemFacets? = null,
    ): WorksRead {
        if (ids.size > TaskPortions.MOST) throw TooManyWorks(store.id, ids.size, TaskPortions.MOST)
        return WorksRead(
            store,
            encoder,
            plugins.imageEncoder()?.id,
            stores.typeOf(store.id).embeddings.ids(),
            ids,
            characters,
            if (card && inputs) cards.getIfAvailable()?.cublas() else null,
            inputs,
            known,
        )
    }

    /**
     * EVERY WORK OF THE [store], [most] of them at a time in the order of their ids
     * ([ItemKeyList.keysAfter][io.github.vlsergey.recommend4me.item.ItemKeyList.keysAfter]): the same works in the same
     * portions at every walk while the source does not change, each a query by the table's key, nothing held between
     * them.
     */
    fun forEachPortion(store: SourceStore, action: (List<String>) -> Unit) {
        var after: String? = null
        do {
            val keys = store.items.keysAfter(after, most)
            after = keys.lastOrNull()?.id
            if (keys.isNotEmpty()) action(keys.map { it.id })
        } while (keys.size == most)
    }

    /** The characters of the dictionary of the [store]'s type, for one operation to read once ([CharactersRead]). */
    fun charactersRead(store: SourceStore) = CharactersRead(stores.typeOf(store.id).universes)
}

/** A reading of more works at once than a portion holds refused ([WorkPasses]): the memory holds a portion. */
class TooManyWorks(source: String, works: Int, most: Int) :
    IllegalStateException("$source: a reading of $works works at once refused, a portion holds $most")
