package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.FandomLikenessApi
import io.github.vlsergey.recommend4me.api.model.FandomWayComparison as ApiFandomWayComparison
import io.github.vlsergey.recommend4me.api.model.FandomWayFound
import io.github.vlsergey.recommend4me.api.model.MethodChoice
import java.time.ZoneOffset
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * THE LIKENESS OF THE FANDOMS' NAMES ASKED FOR ON THE PAGE OF THE MODELS, of the type that keeps the universes: learnt
 * again, the ways a site's fandom is read compared, one of them chosen.
 */
@RestController
class FandomLikenessController(
    private val states: FandomLikenessStates,
    private val learning: FandomLikenessLearning,
    private val comparisons: FandomWayComparisons,
    private val ways: FandomWays,
) : FandomLikenessApi {

    override fun fitFandomLikeness(type: String): ResponseEntity<Unit> {
        states.keeper(type) ?: return ResponseEntity.notFound().build()
        learning.asked()
        return ResponseEntity.noContent().build()
    }

    override fun getFandomWayComparison(type: String): ResponseEntity<ApiFandomWayComparison> {
        states.keeper(type) ?: return ResponseEntity.notFound().build()
        return comparisons.last()?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun startFandomWayComparison(type: String): ResponseEntity<ApiFandomWayComparison> {
        states.keeper(type) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(comparisons.start().toApi())
    }

    override fun chooseFandomWay(type: String, methodChoice: MethodChoice): ResponseEntity<Unit> {
        val way = FandomWay.entries.firstOrNull { it.name == methodChoice.method }
        return when {
            states.keeper(type) == null -> ResponseEntity.notFound().build()
            way == null -> ResponseEntity.badRequest().build()
            else -> {
                ways.choose(way)
                ResponseEntity.noContent().build()
            }
        }
    }

    private fun FandomWayComparison.toApi() =
        ApiFandomWayComparison(
            startedAt = startedAt.atOffset(ZoneOffset.UTC),
            finishedAt = finishedAt?.atOffset(ZoneOffset.UTC),
            millis = millis,
            error = error,
            ways =
                found.map { (way, f) ->
                    FandomWayFound(FandomWayFound.Way.valueOf(way.name), first = f?.first, reciprocal = f?.reciprocal)
                },
        )
}
