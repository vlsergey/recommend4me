package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.manner.WorkMannerChangeEvent
import io.github.vlsergey.recommend4me.picture.SiteItemPictureAnalysisEvent
import io.github.vlsergey.recommend4me.setvector.SiteItemSetVectorChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkFeatureCounts::class.java)

/**
 * THE WORKS' FEATURES MADE AGAIN BY NO WORD OF THE USER'S — their pictures analysed, their sets' vectors, the manners
 * of their texts —, counted beside the models of the grades and of interest ([ModelAutoChanges]): they are trained
 * again once the changes come to a hundredth of the grades they learnt of (owner, 2026-11-24: the sets of a chapter of
 * a rated work trained them again, and every work's expectation was made anew).
 */
@Component
class WorkFeatureCounts(private val recommendations: Recommendations, private val stores: Stores) {

    /** The works' pictures analysed: features of the works, counted. */
    @EventListener
    fun picturesAnalysed(event: SiteItemPictureAnalysisEvent) =
        counted(stores.typeOf(event.source).id, "pictures", event.ids.size)

    /** The vectors of the works' sets made again: features of the works, counted. */
    @EventListener
    fun setVectorsChanged(event: SiteItemSetVectorChangeEvent) = counted(event.type, "setVectors", event.items)

    /** The manners of the works' texts were made again: inputs of the works, counted. */
    @EventListener fun mannersChanged(event: WorkMannerChangeEvent) = counted(event.type, "manner", event.items)

    /**
     * [changes] of the works' features of no word of the user's, for the [reason]: counted beside the models, trained
     * again once they come to a hundredth of the grades they learnt of ([ModelAutoChanges]; owner, 2026-11-24: the sets
     * of a chapter of a rated work trained them again, and every work's expectation was made anew).
     */
    private fun counted(type: String, reason: String, changes: Int) {
        if (changes == 0) return
        val due = stores.contentType(type).autoChanges.count(ModelAutoChanges.GRADES, changes)
        log.info("{}: {} items have new {}: {}", type, changes, reason, if (due) "retraining" else "counted")
        if (due) recommendations.scheduleRetrain(type, reason, byUser = false)
    }
}
