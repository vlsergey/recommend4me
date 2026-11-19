package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.WorkChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FandomShownNames::class.java)

/**
 * THE NAME EVERY UNIVERSE IS SHOWN BY, WORKED OUT AHEAD AND KEPT WITH IT (user, 2026-11-17): its own, else the name of
 * the first entry of the catalogue it is bound to in the user's languages, else the one worked out of its sites'
 * fandoms ([FandomNames.computed]) — a page of the list names its universes by their ids alone ([FandomNames.of]).
 * Worked out again in the queue of the computations after a quiet, of every universe at once, the changed alone
 * written: on the start, on a change of the dictionary (told as every work of a source changed), and as works come and
 * bind new fandoms.
 */
@Component
class FandomShownNames(private val stores: Stores, private val tasks: Tasks) {

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WORK.what) { _, reason -> plan(reason) }
    }

    @EventListener fun itemsChanged(event: WorkChangeEvent) = plan(if (event.ids.isEmpty()) "universes" else "items")

    private fun plan(reason: String) {
        if (stores.types.none { it.type.universes }) return
        tasks.plan(WORK, Instant.now().plusSeconds(QUIET_SECONDS), reason) { keep() }
    }

    /** Every universe's name worked out now, those that changed kept. */
    fun keep() {
        val universes = stores.types.firstOrNull { it.type.universes }?.universes ?: return
        val started = System.currentTimeMillis()
        val dictionary = universes.dictionary()
        val changed =
            universes.shown
                .computed(dictionary)
                .mapNotNull { (value, name) ->
                    FandomRepository.idOf(value)
                        ?.takeIf { dictionary.fandoms[it]?.shownName != name }
                        ?.let { it to name }
                }
                .toMap()
        universes.fandoms.shownNames(changed)
        log.info(
            "the names of {} universes worked out in {} ms, {} of them changed",
            dictionary.fandoms.size,
            System.currentTimeMillis() - started,
            changed.size,
        )
    }

    private companion object {
        val WORK = Task(TaskKind.NAME_FANDOMS)

        /**
         * The quiet after a change before the names are worked out again: the changes of an import's batches at once.
         */
        const val QUIET_SECONDS = 30L
    }
}
