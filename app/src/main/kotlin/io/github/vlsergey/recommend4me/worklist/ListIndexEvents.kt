package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.cast.SiteItemCastEncodeEvent
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.model.WorkPredictionChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterVectorChangeEvent
import io.github.vlsergey.recommend4me.rating.WorkGradeChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT CHANGED THAT THE INDEX OF THE LIST HOLDS ([ListIndexing]), each change a reason the interface words: an item's
 * data, the values the model gives it, an answer — its document made anew —; a grade, a mark of interest, the
 * predictions — its work's values alone ([WorkValues]) —; a regrouping of the works; an index of another format, of
 * every item.
 */
@Component
internal class ListIndexEvents(
    private val stores: Stores,
    private val indexing: ListIndexing,
    private val values: WorkValues,
) {
    /**
     * Of every type whose index is of another format, or none, every item written anew — the tasks over the works
     * following it ([WorkFields]). What the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.types
            .filter { !indexing.current(it.id) }
            .forEach { type -> indexing.change(type.id, ListIndexing.FORMAT) { whole += type.sources.map { it.id } } }

    @EventListener
    fun itemsChanged(event: WorkChangeEvent) =
        indexing.change(stores.typeOf(event.source).id, "items") {
            if (event.ids.isEmpty()) whole += event.source else rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /**
     * The windows of the works' chapters encoded: what the models and the search read of them changed — their documents
     * made anew, every stage of them to be done again.
     */
    @EventListener
    fun chaptersEncoded(event: SiteItemChapterVectorChangeEvent) =
        indexing.change(stores.typeOf(event.source).id, "parts") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    /** The texts of the works' chapters written: the words and the characters of their texts among their numbers. */
    @EventListener
    fun chaptersWritten(event: SiteItemChapterChangeEvent) =
        indexing.change(stores.typeOf(event.source).id, "parts") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    @EventListener
    fun castNamesEncoded(event: SiteItemCastEncodeEvent) =
        indexing.change(stores.typeOf(event.source).id, "castNames") {
            rows.getOrPut(event.source) { HashSet() } += event.ids
        }

    @EventListener
    fun answered(event: WorkFieldChangeEvent) =
        indexing.change(event.type, "answer") { rows.getOrPut(event.source) { HashSet() } += event.itemId }

    @EventListener
    fun gradesChanged(event: WorkGradeChangeEvent) =
        values.change(
            event.type,
            if (event.merged) "regrouped" else "grade",
            listOf(ItemKey(event.source, event.itemId)),
        )

    @EventListener
    fun interestChanged(event: WorkInterestChangeEvent) =
        values.change(
            event.type,
            if (event.merged) "regrouped" else "interest",
            listOf(ItemKey(event.source, event.itemId)),
        )

    @EventListener
    fun predictionsChanged(event: WorkPredictionChangeEvent) = values.change(event.type, "models", event.keys)

    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) = indexing.change(event.type, "regrouped") { regrouped = true }
}
