package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.interest.InterestChanged
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/** A grade or a mark is of a work: every item of it told, whatever item it was given through. */
@Component
class WorkEvents(private val stores: Stores, private val works: Works, private val events: ApplicationEventPublisher) {

    fun gradesChanged(key: ItemKey) {
        val type = stores.typeOf(key.source)
        works.of(type).members(key).forEach { events.publishEvent(GradesChanged(type.id, it.source, it.id)) }
    }

    fun interestChanged(key: ItemKey) {
        val type = stores.typeOf(key.source)
        works.of(type).members(key).forEach { events.publishEvent(InterestChanged(type.id, it.source, it.id)) }
    }

    /** Two works merged: each item may stand under a grade and a mark it did not have. */
    fun merged(key: ItemKey) {
        gradesChanged(key)
        interestChanged(key)
    }
}
