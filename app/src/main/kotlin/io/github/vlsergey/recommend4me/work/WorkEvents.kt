package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.rating.WorkGradeChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/** A grade or a mark is of a work: every item of it told, whatever item it was given through. */
@Component
class WorkEvents(private val stores: Stores, private val works: Works, private val events: ApplicationEventPublisher) {

    /** The user graded the work of the item [key] — or, [merged], it was merged with another. */
    fun gradesChanged(key: ItemKey, merged: Boolean = false) {
        val type = stores.typeOf(key.source)
        works.of(type).members(key).forEach {
            events.publishEvent(WorkGradeChangeEvent(type.id, it.source, it.id, merged))
        }
    }

    /** The user marked the work of the item [key] of interest — or, [merged], it was merged with another. */
    fun interestChanged(key: ItemKey, merged: Boolean = false) {
        val type = stores.typeOf(key.source)
        works.of(type).members(key).forEach {
            events.publishEvent(WorkInterestChangeEvent(type.id, it.source, it.id, merged))
        }
    }

    /**
     * Two works merged: each item may stand under a grade and a mark it did not have — no grade or mark given, the user
     * waits for none of it learnt (owner, 2026-11-19: "I ordered nothing", of the training asked after pairs answered).
     * Of a work merged the user neither graded, marked of interest nor answered on, nothing told: the models learn
     * nothing of it (owner, 2026-11-20: "the model is worth training again only if the works were marked or graded").
     */
    fun merged(key: ItemKey) {
        val s = stores.source(key.source) ?: return
        val marked =
            s.ratings.ofItem(key.id).isNotEmpty() ||
                s.interest.ofItem(key.id) != null ||
                s.corrections.facetsOf(key.id).isNotEmpty()
        if (!marked) return
        gradesChanged(key, merged = true)
        interestChanged(key, merged = true)
    }
}
