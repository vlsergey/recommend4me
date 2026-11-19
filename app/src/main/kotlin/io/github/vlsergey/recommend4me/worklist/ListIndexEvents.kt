package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.cast.CastNamesEncoded
import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.interest.InterestChanged
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.model.PredictionsChanged
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetModelsFitted
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.work.WorksRegrouped
import io.github.vlsergey.recommend4me.workload.WorkKeys
import io.github.vlsergey.recommend4me.workload.Workload
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
    private val workload: Workload,
) {
    /**
     * What the last run of the application left undone planned again — what it named the queue kept ([WorkKeys.want]);
     * and of every type whose index is of another format, or none, every item. Nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        workload.left(ListIndexing.WORK).forEach { l ->
            l.work.type?.let { t -> l.reasons.keys.forEach { indexing.plan(t, it) } }
        }
        stores.types
            .filter { !indexing.current(it.id) }
            .forEach { type -> indexing.change(type.id, ListIndexing.FORMAT) { whole += type.sources.map { it.id } } }
    }

    @EventListener
    fun itemsChanged(event: ItemsChanged) =
        indexing.change(stores.typeOf(event.source).id, "items") {
            if (event.ids.isEmpty()) whole += event.source else rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /** What the models read of the works changed: their documents made anew, of the values they give them now. */
    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) =
        indexing.change(stores.typeOf(event.source).id, "texts") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    @EventListener
    fun castNamesEncoded(event: CastNamesEncoded) =
        indexing.change(stores.typeOf(event.source).id, "castNames") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /** The models of a source fitted anew: the values of every work filled, last of the queue. */
    @EventListener fun modelsFitted(event: FacetModelsFitted) = filling.fill(event.source, "chances")

    @EventListener
    fun answered(event: FacetAnswered) =
        indexing.change(event.type, "answer") { rows.getOrPut(event.source) { HashSet() } += event.itemId }

    @EventListener
    fun gradesChanged(event: GradesChanged) =
        indexing.change(event.type, "grade") { values += ItemKey(event.source, event.itemId) }

    @EventListener
    fun interestChanged(event: InterestChanged) =
        indexing.change(event.type, "interest") { values += ItemKey(event.source, event.itemId) }

    @EventListener
    fun predictionsChanged(event: PredictionsChanged) = indexing.change(event.type, "models") { values += event.keys }

    @EventListener fun regrouped(event: WorksRegrouped) = indexing.change(event.type, "regrouped") { regrouped = true }
}
