package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.SiteItemRemoveEvent
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.WorkGroupChangeEvent
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import io.github.vlsergey.recommend4me.worklist.markedAmong
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(GroupMannerChanges::class.java)

/**
 * THE MANNERS OF THE AUTHORS AND OF THE SERIES WHOSE LISTS OF WORKS CHANGED (owner, 2026-11-27: "the timestamp of the
 * update of the list of works"): a consumer of the dates of those lists — of the normalized authors
 * (`normalized_author.works_at`: a work naming them or not any more, an author bound, merged or taken apart, a work of
 * theirs removed) and of the normalized series (`normalized_series.works_at`) — taking them in the order of those dates
 * and their numbers from where it stands, a [PORTION] of each a turn, their manners made anew of what they hold now
 * ([MannerGroups]) and the works of those that changed dated so ([WorkDatum.GROUPS]): the expectations read them. Where
 * it stands is kept with the marks of the index of the list of the type, of the authors and of the series apart. Of no
 * words of the manners yet, nothing: the stage of the manners makes them all once the words are fitted ([Manners]).
 */
@Component
class GroupMannerChanges(
    private val stores: Stores,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(KIND) { left, reason -> left.work.type?.let { plan(it, reason) } }
    }

    /**
     * Works merged, taken apart, put in a series or out of one, authors merged or taken apart: the manners of the
     * authors gone taken away at once, of no work now.
     */
    @EventListener
    fun regrouped(event: WorkGroupChangeEvent) {
        if (event.goneAuthors.isNotEmpty())
            stores.type(event.type)?.groupManners?.remove(MannerGroup.AUTHOR, event.goneAuthors.map { "$it" })
        plan(event.type, "regrouped")
    }

    /** Items written: an author named or not any more, a work moved to another series. */
    @EventListener fun itemsWritten(event: SiteItemWriteEvent) = plan(stores.typeOf(event.source).id, "items")

    /** An item removed: its authors and its series hold one work fewer. */
    @EventListener fun removed(event: SiteItemRemoveEvent) = plan(event.type, "removal")

    /**
     * The manners of the groups of the [typeId] whose lists of works changed planned, after a quiet, for the [reason].
     */
    fun plan(typeId: String, reason: String) =
        tasks.plan(Task(KIND, type = typeId), Instant.now().plusSeconds(QUIET_SECONDS), reason) {
            if (turn(typeId)) tasks.again("portion")
        }

    /**
     * ONE TURN: the next [PORTION] authors and series of the [typeId] whose lists of works changed after where it
     * stands, their manners made anew; whether more are left.
     */
    internal fun turn(typeId: String): Boolean {
        val type = stores.type(typeId)
        val words = type?.mannerWords?.id()
        if (type == null || words == null) return false
        val started = System.currentTimeMillis()
        val index = lists.of(typeId)
        val authors = type.authors.works.changedAfter(GroupCursor.parse(index.stages.text(AUTHORS)), PORTION)
        val series = type.series.worksChangedAfter(GroupCursor.parse(index.stages.text(SERIES)), PORTION)
        val groups =
            authors.map { GroupRef(MannerGroup.AUTHOR, "${it.id}") } +
                series.map { GroupRef(MannerGroup.SERIES, "${it.id}") }
        val made = MannerGroups.refresh(type, words, groups.toSet())
        if (made.dated.isNotEmpty()) type.works.changed(made.dated, WorkDatum.GROUPS, Instant.now())
        // Of the works the models of the grades learn of alone: the manners of the others train them nothing
        val learnt = markedAmong(index, made.dated)
        if (learnt.isNotEmpty()) events.publishEvent(WorkMannerChangeEvent(typeId, learnt.size))
        authors.lastOrNull()?.let { index.stages.changed(AUTHORS, it.toString()) }
        series.lastOrNull()?.let { index.stages.changed(SERIES, it.toString()) }
        if (groups.isNotEmpty()) {
            index.commit()
            log.info(
                "{}: of {} authors and {} series of lists of works changed, {} manners made or taken away, in {} ms",
                typeId,
                authors.size,
                series.size,
                made.groups,
                System.currentTimeMillis() - started,
            )
        }
        return authors.size == PORTION || series.size == PORTION
    }

    companion object {
        val KIND = TaskKind.MAKE_GROUP_MANNERS

        /** The authors and the series a turn takes of each. */
        const val PORTION = 1000

        /** The quiet after the last change before the manners are made: a reader reads on. */
        private const val QUIET_SECONDS = 60L

        /** Where the reading of the authors and of the series stands, among the marks of the index of the list. */
        private const val AUTHORS = "groupManners:authors"
        private const val SERIES = "groupManners:series"
    }
}
