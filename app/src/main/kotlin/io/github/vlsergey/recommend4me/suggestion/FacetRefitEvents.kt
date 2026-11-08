package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.CastNamesEncoded
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT THE MODELS OF THE FACETS LEARN FROM CHANGED: the items, their texts' vectors, their parts, the user's answers —
 * each change plans the refit of its source's facets after the quiet ([FacetRefits.changed]), its reason a key the
 * interface words.
 */
@Component
internal class FacetRefitEvents(private val refits: FacetRefits) {
    @EventListener fun itemsChanged(event: ItemsChanged) = refits.changed(event.source, "items")

    @EventListener fun textVectorsChanged(event: TextVectorsChanged) = refits.changed(event.source, "texts")

    @EventListener fun partsSaved(event: PartsSaved) = refits.changed(event.source, "parts")

    /** The names of the works' casts encoded: the models of the characters and pairings read them. */
    @EventListener fun castNamesEncoded(event: CastNamesEncoded) = refits.changed(event.source, "castNames")

    /** The user's answer on a work's value: what the models of the facets learn from. */
    @EventListener fun answered(event: FacetAnswered) = refits.changed(event.source, "answer")
}
