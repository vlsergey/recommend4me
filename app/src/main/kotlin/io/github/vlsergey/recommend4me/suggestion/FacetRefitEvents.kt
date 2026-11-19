package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.SiteItemCastEncodeEvent
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.SiteItemTextVectorChangeEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT THE MODELS OF THE FACETS LEARN FROM CHANGED: the items, their texts' vectors, their parts, the user's answers —
 * each change plans the refit of its source's facets after the quiet ([FacetRefits.changed]), its reason a key the
 * interface words.
 */
@Component
internal class FacetRefitEvents(private val refits: FacetRefits, private val stores: Stores) {
    @EventListener fun itemsChanged(event: WorkChangeEvent) = refits.changed(event.source, "items")

    @EventListener fun textVectorsChanged(event: SiteItemTextVectorChangeEvent) = refits.changed(event.source, "texts")

    @EventListener fun partsSaved(event: SiteItemChapterChangeEvent) = refits.changed(event.source, "parts")

    /** The names of the works' casts encoded: the models of the characters and pairings read them. */
    @EventListener fun castNamesEncoded(event: SiteItemCastEncodeEvent) = refits.changed(event.source, "castNames")

    /** The user's answer on a work's value: what the models of the facets learn from. */
    @EventListener fun answered(event: WorkFieldChangeEvent) = refits.changed(event.source, "answer")
}
