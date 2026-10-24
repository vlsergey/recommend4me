package io.github.vlsergey.recommend4me.pairing

import io.github.vlsergey.recommend4me.api.PairingsApi
import io.github.vlsergey.recommend4me.api.model.NewPairing
import io.github.vlsergey.recommend4me.api.model.Pairing
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The pairings known, and a new one made ([Pairings]). */
@RestController
class PairingsController(private val pairings: Pairings) : PairingsApi {

    override fun listPairings(): ResponseEntity<List<Pairing>> = ResponseEntity.ok(pairings.list())

    override fun makePairing(newPairing: NewPairing): ResponseEntity<Pairing> = ResponseEntity.ok(pairings.make(newPairing.first, newPairing.second))
}
