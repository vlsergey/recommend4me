package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.cast.SiteItemCastEncodeEvent
import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
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
 * data, the values the model gives it, an answer, a grade, a mark of interest — its date set, its work's documents made
 * anew —; a regrouping of the works; an index of another format, of every item. The predictions are written into the
 * index by what makes them ([ListPredictions]).
 */
@Component
internal class ListIndexEvents(
    private val stores: Stores,
    private val indexing: ListIndexing,
) {
    /**
     * Of every type whose index is of another format, or none, every item written anew — the tasks over the works
     * following it ([WorkFields]); of every other, what changed since its index was last written. What the data say,
     * nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.types.forEach { type ->
            when {
                !indexing.current(type.id) -> indexing.plan(type.id, ListIndexing.FORMAT)
                indexing.pending(type) -> indexing.plan(type.id, "start")
            }
        }

    @EventListener
    fun itemsChanged(event: WorkChangeEvent) = indexing.itemsChanged(source(event.source), event.ids, "items")

    /**
     * The windows of the works' chapters encoded: what the models and the search read of them changed — the items
     * dated, every stage of their works done before it to be done again ([StageBehind]).
     */
    @EventListener
    fun chaptersEncoded(event: SiteItemChapterVectorChangeEvent) =
        indexing.itemsChanged(source(event.source), event.ids, "parts")

    /**
     * The texts of the works' chapters written: the words and the characters of their texts among their numbers, the
     * item dated by their writing as they change ([io.github.vlsergey.recommend4me.part.PartCounts.totalled]).
     */
    @EventListener
    fun chaptersWritten(event: SiteItemChapterChangeEvent) = indexing.plan(stores.typeOf(event.source).id, "parts")

    @EventListener
    fun castNamesEncoded(event: SiteItemCastEncodeEvent) =
        indexing.itemsChanged(source(event.source), event.ids, "castNames")

    @EventListener
    fun answered(event: WorkFieldChangeEvent) =
        indexing.worksChanged(stores.type(event.type)!!, listOf(ItemKey(event.source, event.itemId)), "answer")

    @EventListener
    fun gradesChanged(event: WorkGradeChangeEvent) =
        indexing.worksChanged(
            stores.type(event.type)!!,
            listOf(ItemKey(event.source, event.itemId)),
            if (event.merged) "regrouped" else "grade",
        )

    @EventListener
    fun interestChanged(event: WorkInterestChangeEvent) =
        indexing.worksChanged(
            stores.type(event.type)!!,
            listOf(ItemKey(event.source, event.itemId)),
            if (event.merged) "regrouped" else "interest",
        )

    /**
     * Works put together or apart: two works merged or an item taken out dated by it
     * ([io.github.vlsergey.recommend4me.work.WorkMembership]); of works put in a series, of authors, nothing to write.
     */
    @EventListener fun regrouped(event: WorkGroupChangeEvent) = indexing.plan(event.type, "regrouped")

    private fun source(id: String) = stores.source(id)!!
}
