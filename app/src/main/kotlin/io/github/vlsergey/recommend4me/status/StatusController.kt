package io.github.vlsergey.recommend4me.status

import io.github.vlsergey.recommend4me.api.StatusApi
import io.github.vlsergey.recommend4me.api.model.Status
import io.github.vlsergey.recommend4me.api.model.WorkStatus
import io.github.vlsergey.recommend4me.part.PartVectors
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.setvector.SetVectors
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** How far the background work has got: pictures analysed, texts and parts encoded, sets made. */
@RestController
class StatusController(
    private val stores: Stores,
    private val plugins: Plugins,
    private val pictures: PictureService,
    private val textVectors: TextVectors,
    private val parts: PartVectors,
    private val sets: SetVectors,
) : StatusApi {

    override fun getStatus(): ResponseEntity<Status> {
        val p = pictures.status()
        val encoder = plugins.textEncoder()
        val texts = encoder?.let { e -> stores.sources.sumOf { it.textVectors.count(e.id) } } ?: 0
        val partsTotal = parts.total()
        return ResponseEntity.ok(
            Status(
                listOf(
                    WorkStatus("pictures", p.analyzed, p.total, p.running, p.failed, p.perMinute),
                    WorkStatus("texts", texts, texts, textVectors.running()),
                    WorkStatus("parts", partsTotal - parts.pending(), partsTotal, parts.running()),
                    WorkStatus("sets", sets.done, sets.total, sets.running()),
                ),
            ),
        )
    }
}
