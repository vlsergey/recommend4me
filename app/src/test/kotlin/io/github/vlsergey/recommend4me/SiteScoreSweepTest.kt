package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.AverageWork
import io.github.vlsergey.recommend4me.model.Averages
import io.github.vlsergey.recommend4me.model.EveryWork
import io.github.vlsergey.recommend4me.model.NumberSpread
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.model.SweepState
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import io.github.vlsergey.recommend4me.workload.inBackground
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * The training and the sweep of the scores apart: a training reads the graded works alone with the average work kept,
 * the sweep scores every work in turns of the queue from where it stopped, counts the average work along, and begins
 * again by a new model.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/site-sweep"])
class SiteScoreSweepTest : SiteTestBase() {
    @Autowired lateinit var everyWork: EveryWork
    @Autowired lateinit var plugins: Plugins
    @Autowired lateinit var workload: Workload

    private val type
        get() = stores.type("books")!!

    private val basis
        get() = AverageWork.basis(plugins, type)

    @Test
    fun `a training reads the graded works with the average work kept, and the sweep scores every work in turns`() {
        catalogue()
        (3..8).forEach { k ->
            gradesController.rateItem("site", "s$k", RatingRequest(5))
            gradesController.rateItem("site", "d$k", RatingRequest(1))
        }
        // The first training finds no average work kept: counted of every work once, and kept
        inBackground { recommendations.retrain("books") }
        assertNotNull(type.averageWork.load(basis))
        // The sweep in one go: every work scored, a prediction of an item gone removed, the average work counted
        // along kept, as the full pass counts it
        type.predictions.write(listOf(ItemKey("site", "zz-gone") to Predicted(5.0, null)))
        val full = inBackground {
            workload.run(Work("sweepInOneGo")) {
                inOneGo()
                everyWork.averageWork(type)
            }
        }
        val inOneGo = type.predictions.predicted("site")
        assertEquals(stores.source("site")!!.items.keys().map { it.id }.toSet(), inOneGo.keys)
        val swept = assertNotNull(type.averageWork.load(basis))
        assertAlike(full, swept)

        trainedWithTheAverageKept(swept)
        inTurns(inOneGo.keys)
    }

    /**
     * A training takes the average work kept and counts none: a number of it no work has is the model's — and the
     * training takes seconds, not a pass over every work.
     */
    private fun trainedWithTheAverageKept(swept: AverageWork) {
        val probe = "probe"
        type.averageWork.save(
            AverageWork(
                Averages(
                    swept.averages.vectors,
                    swept.averages.numbers + (probe to NumberSpread(42f, 1f)),
                    swept.averages.shares,
                ),
                swept.pictureCenter,
            ),
            basis,
        )
        inBackground { recommendations.retrain("books") }
        assertEquals(NumberSpread(42f, 1f), recommendations.summary("books").meta!!.numbers[probe])
        // The sweep the training began counts the average work anew, and keeps it
        inBackground { scoreSweep.sweepNow("books") }
        assertNull(type.averageWork.load(basis)!!.averages.numbers[probe])
    }

    /**
     * The sweep in turns of one portion of a few works each, every turn going on from where the one before stopped: the
     * predictions and the average work as in one go. A model trained between two turns begins it again from the first
     * work. The turns inside a work of the queue of their own: none planned by them, nor any other work, runs between.
     */
    private fun inTurns(items: Set<String>) {
        val cursors = ArrayList<String?>()
        var restarted: SweepState? = null
        val full = inBackground {
            workload.run(Work("sweepInTurns")) {
                type.predictions.remove("site", items)
                val model = type.models.trainedAt()!!
                type.sweep.save(SweepState(model, basis, null, Instant.now(), 0, 0, null))
                repeat(2) {
                    scoreSweep.step("books", Duration.ZERO, PORTION)
                    cursors += type.sweep.load()!!.at?.after
                }
                // Another model trained: the sweep under way, of the model before, begins again from the first work
                val under = type.sweep.load()!!
                recommendations.retrain("books")
                assertNotEquals(model, type.models.trainedAt())
                type.sweep.save(under)
                scoreSweep.step("books", Duration.ZERO, PORTION)
                restarted = type.sweep.load()
                while (scoreSweep.sweeping("books")) scoreSweep.step("books", Duration.ZERO, PORTION)
                everyWork.averageWork(type)
            }
        }
        assertTrue(cursors.zipWithNext().all { (a, b) -> a != null && b != null && a < b }, "cursors $cursors")
        assertEquals(type.models.trainedAt(), restarted!!.model)
        assertEquals(cursors.first(), restarted!!.at?.after)
        assertEquals(PORTION, restarted!!.scored)
        // Of the new model every work scored, the average work as the full pass counts it
        assertEquals(items, type.predictions.predicted("site").keys)
        assertAlike(full, type.averageWork.load(basis)!!)
        // The same model in turns as in one go
        val (again, turned) =
            inBackground {
                workload.run(Work("sweepInTurns")) {
                    inOneGo()
                    val again = type.predictions.predicted("site")
                    type.predictions.remove("site", again.keys)
                    type.sweep.save(SweepState(type.models.trainedAt()!!, basis, null, Instant.now(), 0, 0, null))
                    while (scoreSweep.sweeping("books")) scoreSweep.step("books", Duration.ZERO, PORTION)
                    again to type.predictions.predicted("site")
                }
            }
        assertEquals(again.keys, turned.keys)
        again.forEach { (id, p) ->
            assertTrue(abs(p.score - turned.getValue(id).score) < CLOSE, "$id: ${p.score} ${turned.getValue(id).score}")
        }
    }

    /** The two average works alike, within the last digits of a sum taken in another order. */
    private fun assertAlike(a: AverageWork, b: AverageWork) {
        assertEquals(a.averages.vectors.keys, b.averages.vectors.keys)
        a.averages.vectors.forEach { (block, spread) ->
            val other = b.averages.vectors.getValue(block)
            assertTrue(abs(spread.spread - other.spread) < CLOSE, block)
            assertTrue(spread.center.indices.all { abs(spread.center[it] - other.center[it]) < CLOSE }, block)
        }
        assertEquals(a.averages.numbers.keys, b.averages.numbers.keys)
        assertEquals(a.averages.shares, b.averages.shares)
        assertEquals(a.pictureCenter?.size, b.pictureCenter?.size)
        a.pictureCenter?.let { center ->
            assertTrue(center.indices.all { abs(center[it] - b.pictureCenter!![it]) < CLOSE })
        }
    }

    /**
     * Every work scored again by the current model, the sweep begun anew and run to its end in one turn: inside a work
     * of the queue, on its thread.
     */
    private fun inOneGo() {
        recommendations.scoreAll("books", "asked")
        scoreSweep.sweepNow("books")
    }

    private companion object {
        const val CLOSE = 1e-4

        /** The works of a turn of the sweep in turns. */
        const val PORTION = 7
    }
}
