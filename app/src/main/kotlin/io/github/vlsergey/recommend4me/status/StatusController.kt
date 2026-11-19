package io.github.vlsergey.recommend4me.status

import io.github.vlsergey.recommend4me.api.StatusApi
import io.github.vlsergey.recommend4me.api.model.Status
import io.github.vlsergey.recommend4me.api.model.WorkStatus
import io.github.vlsergey.recommend4me.part.PartVectors
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.setvector.SetVectorMaking
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * How far the background work has got, of what is known at once (user, 2026-11-18, of a status counting millions of
 * rows at every read: "why does it need those rows at all?"; "drop what cannot be taken at once"): the pictures — their
 * tables small, counted by their index — and the sets, of the pass running; of the texts and the parts only whether
 * they are encoded now: what is left of them is the queue's to tell.
 */
@RestController
class StatusController(
    private val pictures: PictureService,
    private val textVectors: TextVectors,
    private val parts: PartVectors,
    private val sets: SetVectorMaking,
) : StatusApi {

    override fun getStatus(): ResponseEntity<Status> {
        val p = pictures.status()
        return ResponseEntity.ok(
            Status(
                listOf(
                    WorkStatus("pictures", p.analyzed, p.total, p.running, p.failed, p.perMinute),
                    WorkStatus("texts", 0, 0, textVectors.running),
                    WorkStatus("parts", 0, 0, parts.running()),
                    WorkStatus("sets", 0, 0, sets.running()),
                )
            )
        )
    }
}
