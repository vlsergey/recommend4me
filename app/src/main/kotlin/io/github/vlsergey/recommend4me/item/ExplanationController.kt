package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.ExplanationApi
import io.github.vlsergey.recommend4me.api.model.ContributionGroup as ApiContributionGroup
import io.github.vlsergey.recommend4me.api.model.HeldOut as ApiHeldOut
import io.github.vlsergey.recommend4me.model.ContributionGroup
import io.github.vlsergey.recommend4me.model.Explanations
import io.github.vlsergey.recommend4me.model.HeldOutModels
import io.github.vlsergey.recommend4me.model.HeldOutRead
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * What moves the prediction of a work, and of a graded one what the model that had not seen it says — worked out in the
 * queue of the computations, 202 till it is there.
 */
@RestController
class ExplanationController(
    private val stores: Stores,
    private val heldOuts: HeldOutModels,
    private val explanations: Explanations,
) : ExplanationApi {

    override fun getExplanation(source: String, item: String): ResponseEntity<List<ApiContributionGroup>> =
        stores.answerOn(source, item) { _, _ ->
            val key = ItemKey(source, item)
            // Of a graded work, the model's that had not seen it, once worked out
            when (val h = heldOuts.heldOut(key)) {
                is HeldOutRead.Ready -> explained(h.heldOut.explanation)
                HeldOutRead.Planned -> ResponseEntity.accepted().build()
                HeldOutRead.None -> explained(explanations.explanation(key))
            }
        }

    /** The parts of an explanation, without their features. */
    private fun explained(groups: List<ContributionGroup>): ResponseEntity<List<ApiContributionGroup>> =
        ResponseEntity.ok(groups.map { it.toApi(withFeatures = false) })

    override fun getHeldOut(source: String, item: String): ResponseEntity<ApiHeldOut> =
        stores.answerOn(source, item) { _, _ ->
            when (val h = heldOuts.heldOut(ItemKey(source, item))) {
                is HeldOutRead.Ready ->
                    ResponseEntity.ok(
                        ApiHeldOut(h.heldOut.score, h.heldOut.explanation.map { it.toApi() }, h.heldOut.gradePlace)
                    )
                HeldOutRead.Planned -> ResponseEntity.accepted().build()
                HeldOutRead.None -> ResponseEntity.noContent().build()
            }
        }
}
