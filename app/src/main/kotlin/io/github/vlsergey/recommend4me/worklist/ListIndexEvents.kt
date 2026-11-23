package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.picture.SiteItemPictureAnalysisEvent
import io.github.vlsergey.recommend4me.rating.WorkGradeChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagChangeEvent
import io.github.vlsergey.recommend4me.universe.UniverseChangeEvent
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT CHANGED THAT THE INDEX OF THE LIST HOLDS ([ListIndexing]), each change a reason the interface words: an item's
 * data, the values the model gives it, an answer, a grade, a mark of interest — its date set, its work's documents made
 * anew —; a regrouping of the works; an index of no date of writing, of every item. The predictions are written into
 * the index by what makes them ([ListPredictions]).
 */
@Component
internal class ListIndexEvents(
    private val stores: Stores,
    private val indexing: ListIndexing,
) {
    /**
     * Of every type, what changed since its index was last written — of an index of no date of writing, every item read
     * and compared ([ListIndexing.pending]). What the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.filter(indexing::pending).forEach { indexing.plan(it.id, "start") }

    /** What the site says of items, or what is drafted of them, changed. */
    @EventListener
    fun itemsChanged(event: WorkChangeEvent) =
        indexing.itemsChanged(source(event.source), event.ids, WorkDatum.DATA, "items")

    /**
     * The texts of the works' chapters written: what their windows and the search read; the words and the characters of
     * their texts among their numbers dated as they change
     * ([io.github.vlsergey.recommend4me.part.PartCounts.totalled]).
     */
    @EventListener
    fun chaptersWritten(event: SiteItemChapterChangeEvent) =
        indexing.itemsChanged(source(event.source), event.ids, WorkDatum.CHAPTERS, "parts")

    /**
     * The dictionary of the universes changed: the documents it reaches written anew by what their drafts make now
     * ([ListDictionary]), of every type with universes.
     */
    @EventListener(UniverseChangeEvent::class)
    fun universesChanged() = stores.types.filter { it.type.universes }.forEach { indexing.plan(it.id, "universes") }

    /** The tags of the [event]'s type changed: the documents their changes reach written anew ([ListDictionary]). */
    @EventListener fun tagsChanged(event: TagChangeEvent) = indexing.plan(event.type, "tags")

    /** The works' pictures analysed: what their fields, expectations and sets read. */
    @EventListener
    fun picturesAnalysed(event: SiteItemPictureAnalysisEvent) =
        indexing.itemsChanged(source(event.source), event.ids, WorkDatum.PICTURES, "pictures")

    @EventListener
    fun answered(event: WorkFieldChangeEvent) =
        indexing.worksChanged(
            stores.type(event.type)!!,
            listOf(ItemKey(event.source, event.itemId)),
            WorkDatum.ANSWERS,
            "answer",
        )

    @EventListener
    fun gradesChanged(event: WorkGradeChangeEvent) =
        indexing.worksChanged(
            stores.type(event.type)!!,
            listOf(ItemKey(event.source, event.itemId)),
            WorkDatum.GRADES,
            if (event.merged) "regrouped" else "grade",
        )

    @EventListener
    fun interestChanged(event: WorkInterestChangeEvent) =
        indexing.worksChanged(
            stores.type(event.type)!!,
            listOf(ItemKey(event.source, event.itemId)),
            WorkDatum.GRADES,
            if (event.merged) "regrouped" else "interest",
        )

    /**
     * Works put together or apart: two works merged or an item taken out dated by it
     * ([io.github.vlsergey.recommend4me.work.WorkMembership]); of works put in a series, of authors, nothing to write.
     */
    @EventListener fun regrouped(event: WorkGroupChangeEvent) = indexing.plan(event.type, "regrouped")

    private fun source(id: String) = stores.source(id)!!
}
