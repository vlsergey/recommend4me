package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.SiteItemCastEncodeEvent
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterVectorChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.SiteItemTextVectorChangeEvent
import io.github.vlsergey.recommend4me.universe.UniverseChangeEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT THE MODELS OF THE FACETS LEARN FROM CHANGED: the user's answers and the dictionaries the user changed — each
 * plans the refit of its source's facets after the quiet ([FacetRefits.changed]), its reason a key the interface words
 * —, and of no word of the user's the items the sites wrote, their texts' vectors, their parts, the names of their
 * casts — counted beside the models, fitted again at a hundredth of the works they learnt of ([FacetRefits.counted];
 * owner, 2026-11-24: a chapter of a rated work read fitted them again, and every work's fields were made anew). The
 * models learn of the works the user marked — of the answers on their values — and calibrate on the works rated
 * (docs/computations.md, 5 and 7): a change of a work the user neither answered on nor rated changes nothing they learn
 * of, and plans no refit (owner, 2026-11-19: "I hope the chapters read influence the training only if those works were
 * marked or rated").
 */
@Component
internal class FacetRefitEvents(private val refits: FacetRefits, private val stores: Stores) {
    /** The user's word on the items: fitted after the quiet; what the site wrote of them: counted. */
    @EventListener
    fun itemsChanged(event: WorkChangeEvent) {
        if (event.bySite) ofLearnt(event.source, event.ids, "items")
        else if (learnt(event.source, event.ids) > 0) refits.changed(event.source, "items")
    }

    @EventListener
    fun textVectorsChanged(event: SiteItemTextVectorChangeEvent) = ofLearnt(event.source, event.ids, "texts")

    @EventListener
    fun chapterVectorsChanged(event: SiteItemChapterVectorChangeEvent) = ofLearnt(event.source, event.ids, "parts")

    @EventListener fun partsSaved(event: SiteItemChapterChangeEvent) = ofLearnt(event.source, event.ids, "parts")

    /** The names of the works' casts encoded: the models of the characters and pairings read them. */
    @EventListener fun castNamesEncoded(event: SiteItemCastEncodeEvent) = ofLearnt(event.source, event.ids, "castNames")

    /**
     * The dictionary of the universes changed — what the models of the characters and the universes choose from —:
     * every source of a type with universes refitted.
     */
    @EventListener(UniverseChangeEvent::class)
    fun universesChanged() =
        stores.types.filter { it.type.universes }.flatMap { it.sources }.forEach { refits.changed(it.id, "universes") }

    /** The user's answer on a work's value: what the models of the facets learn from. */
    @EventListener fun answered(event: WorkFieldChangeEvent) = refits.changed(event.source, "answer")

    /**
     * The items [ids] of the [source] changed by no word of the user's, for the [reason]: those of works the user
     * answered on or rated counted.
     */
    private fun ofLearnt(source: String, ids: Collection<String>, reason: String) =
        refits.counted(source, reason, learnt(source, ids))

    /**
     * How many of the items [ids] of the [source] are of works the user answered on or rated: what the models learn.
     */
    private fun learnt(source: String, ids: Collection<String>): Int {
        val s = stores.source(source) ?: return 0
        val learnt =
            s.corrections.facetsOf(ids).filterValues { it.isNotEmpty() }.keys +
                s.ratings.ofItems(ids).map { it.itemId } +
                s.interest.ofItems(ids).keys
        return ids.count { it in learnt }
    }
}
