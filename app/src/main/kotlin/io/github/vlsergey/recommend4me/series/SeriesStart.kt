package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK_RELATION
import io.github.vlsergey.recommend4me.work.WorkRelations
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SeriesStart::class.java)

/**
 * The words "one series" said of two works before the normalized series, each of them the works of a group now: the
 * works chained by them — one with a second, the second with a third — one group ([SeriesGroups]), the words gone.
 * Once: no word of the kind is said any more.
 */
@Component
class SeriesStart(private val databases: Databases) {

    @Order(BEFORE_READERS)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val db = databases.dictionaries.dsl
        val r = NORMALIZED_WORK_RELATION
        val words =
            db.select(r.FIRST_WORK, r.OTHER_WORK)
                .from(r)
                .where(r.KIND.eq(TypeLinkRepository.SERIES))
                .fetch { WorkRelations.pair(it.value1()!!, it.value2()!!) to TypeLinkRepository.SERIES }
                .toMap()
        if (words.isEmpty()) return
        // A step of the start, before anything is made of the groups: no one to tell
        val groups = SeriesGroups(db, regrouped = {})
        val now = Instant.now()
        val chains = WorkRelations.chains(words, TypeLinkRepository.SERIES)
        chains.forEach { chain ->
            val first = chain.min()
            chain.filter { it != first }.forEach { groups.together(first, it, now) }
        }
        db.deleteFrom(r).where(r.KIND.eq(TypeLinkRepository.SERIES)).execute()
        log.info("{} words \"one series\" made {} groups of works of one series", words.size, chains.size)
    }

    private companion object {
        /** Among the steps of the start: before every step that reads the pairs. */
        const val BEFORE_READERS = -10
    }
}
