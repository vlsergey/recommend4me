package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.cast.SiteItemCastEncodeEvent
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.model.WorkPredictionChangeEvent
import io.github.vlsergey.recommend4me.rating.WorkGradeChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FieldModelFitEvent
import io.github.vlsergey.recommend4me.textvector.SiteItemTextVectorChangeEvent
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT CHANGED THAT THE INDEX OF THE LIST HOLDS ([ListIndexing]), each change a reason the interface words: an item's
 * data, the values the model gives it, an answer — its document made anew —; a grade, a mark of interest, the
 * predictions of a training — its work's values alone —; a regrouping of the works; an index of another format, of
 * every item.
 */
@Component
internal class ListIndexEvents(
    private val stores: Stores,
    private val indexing: ListIndexing,
    private val filling: ListFilling,
) {
    /**
     * Of every type whose index is of another format, or none, every item written anew, and the values the models give
     * the rated works filled after, last of the queue: an index made anew has none. What the data say, nothing for the
     * start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.types
            .filter { !indexing.current(it.id) }
            .forEach { type ->
                indexing.change(type.id, ListIndexing.FORMAT) { whole += type.sources.map { it.id } }
                type.sources.forEach { filling.fill(it.id, ListIndexing.FORMAT, last = true) }
            }

    @EventListener
    fun itemsChanged(event: WorkChangeEvent) =
        indexing.change(stores.typeOf(event.source).id, "items") {
            if (event.ids.isEmpty()) whole += event.source else rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /** What the models read of the works changed: their documents made anew, of the values they give them now. */
    @EventListener
    fun textVectorsChanged(event: SiteItemTextVectorChangeEvent) =
        indexing.change(stores.typeOf(event.source).id, "texts") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    @EventListener
    fun castNamesEncoded(event: SiteItemCastEncodeEvent) =
        indexing.change(stores.typeOf(event.source).id, "castNames") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /** The models of a source fitted anew: the values of every work filled, last of the queue. */
    @EventListener fun modelsFitted(event: FieldModelFitEvent) = filling.fill(event.source, "chances")

    @EventListener
    fun answered(event: WorkFieldChangeEvent) =
        indexing.change(event.type, "answer") { rows.getOrPut(event.source) { HashSet() } += event.itemId }

    @EventListener
    fun gradesChanged(event: WorkGradeChangeEvent) =
        indexing.change(event.type, "grade") { values += ItemKey(event.source, event.itemId) }

    @EventListener
    fun interestChanged(event: WorkInterestChangeEvent) =
        indexing.change(event.type, "interest") { values += ItemKey(event.source, event.itemId) }

    @EventListener
    fun predictionsChanged(event: WorkPredictionChangeEvent) =
        indexing.change(event.type, "models") { values += event.keys }

    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) = indexing.change(event.type, "regrouped") { regrouped = true }
}
