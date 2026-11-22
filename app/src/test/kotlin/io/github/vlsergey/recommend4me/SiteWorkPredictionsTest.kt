package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.AverageWork
import io.github.vlsergey.recommend4me.model.Averages
import io.github.vlsergey.recommend4me.model.NumberSpread
import io.github.vlsergey.recommend4me.model.SweepState
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.inBackground
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListPredictions
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The training and the expectations of the works apart: a training reads the graded works alone with the average work
 * kept and scores them, the expectations score every work in turns of the queue — the marked and rated, then the others
 * — the highest predicted first, from where the turn before left, count the average work along, and begin a pass anew
 * by a new model.
 */
class SiteWorkPredictionsTest : SiteTestBase() {
    @Autowired lateinit var plugins: Plugins
    @Autowired lateinit var lists: ListIndexes

    private val type
        get() = stores.type("books")!!

    private val basis
        get() = AverageWork.basis(plugins, type)

    @Test
    fun `a training reads the average work kept, the expectations score every work in turns`() {
        catalogue()
        (3..8).forEach { k ->
            gradesController.rateItem("site", "s$k", RatingRequest(5))
            gradesController.rateItem("site", "d$k", RatingRequest(1))
        }
        // What the background makes of them made now: nothing changes them while the average work is compared
        background.run()
        // A training keeps no average work: of none kept it counts the graded works' alone — never a pass over every
        // work inside a training; the pass of the expectations it begins keeps every work's
        val kept = type.averageWork.load(basis)
        inBackground { recommendations.retrain("books") }
        val after = type.averageWork.load(basis)
        if (kept == null) assertNull(after) else assertAlike(kept, assertNotNull(after))
        // The pass in one go: every work scored, the average work counted along kept
        inBackground { tasks.run(Task(TaskKind.PREDICT_WORKS)) { inOneGo() } }
        val inOneGo = predicted()
        assertEquals(stores.source("site")!!.items.keys().map { it.id }.toSet(), inOneGo.keys)
        val swept = assertNotNull(type.averageWork.load(basis))

        trainedWithTheAverageKept(swept)
        inTurns(inOneGo.keys, swept)
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
        // The pass the training began counts the average work anew, and keeps it
        inBackground { workPredictions.predictNow("books") }
        assertNull(type.averageWork.load(basis)!!.averages.numbers[probe])
    }

    /**
     * The pass in turns of one portion of a few works each, every turn going on from where the one before left: the
     * predictions and the average work as in one go. A model trained between two turns begins a pass anew. The turns
     * inside a task of the queue of their own: none planned by them, nor any other task, runs between.
     */
    private fun inTurns(items: Set<String>, full: AverageWork) {
        val scoredSoFar = ArrayList<Int>()
        var restarted: SweepState? = null
        var before: SweepState? = null
        inBackground {
            tasks.run(Task(TaskKind.PREDICT_WORKS)) {
                forget(items)
                workPredictions.begin(type, "asked")
                val model = type.models.trainedAt()!!
                allOf(WorkScope.MARKED)
                var cursor: TaskCursor? = TaskCursor.FIRST
                repeat(2) {
                    cursor = workPredictions.turn("books", WorkScope.OTHER, cursor!!, PORTION)
                    scoredSoFar += type.sweep.load()!!.scored
                }
                // Another model trained: the pass under way, of the model before, begins again from the first work
                before = type.sweep.load()!!
                recommendations.retrain("books")
                assertNotEquals(model, type.models.trainedAt())
                allOf(WorkScope.MARKED)
                workPredictions.turn("books", WorkScope.OTHER, TaskCursor.FIRST, PORTION)
                restarted = type.sweep.load()
                allOf(WorkScope.OTHER)
            }
        }
        assertTrue(scoredSoFar.zipWithNext().all { (a, b) -> a > 0 && b > a }, "scored $scoredSoFar")
        assertEquals(type.models.trainedAt(), restarted!!.model)
        // A pass anew: begun since the one under way, of its first portion alone
        assertTrue(restarted!!.began > before!!.began, "a pass anew")
        assertTrue(restarted!!.scored in 1..scoredSoFar.first(), "scored ${restarted!!.scored}")
        // Of the new model every work scored, the average work as the pass in one go counts it
        assertEquals(items, predicted().keys)
        assertAlike(full, type.averageWork.load(basis)!!)
        // The same model in turns as in one go
        val (again, turned) =
            inBackground {
                tasks.run(Task(TaskKind.PREDICT_WORKS)) {
                    inOneGo()
                    // Of the works compared: the graded are the training's
                    val graded = stores.source("site")!!.ratings.all().map { it.itemId }.toSet()
                    val again = predicted().filterKeys { it !in graded }
                    forget(again.keys)
                    workPredictions.begin(type, "asked")
                    WorkScope.entries.forEach(::allOf)
                    again to predicted().filterKeys { it !in graded }
                }
            }
        assertEquals(again.keys, turned.keys)
        again.forEach { (id, p) ->
            assertTrue(abs(p.score - turned.getValue(id).score) < CLOSE, "$id: ${p.score} ${turned.getValue(id).score}")
        }
    }

    /** The predictions of the site's works as the index of the list holds them, written so far. */
    private fun predicted() = lists.of("books").written { ListPredictions.ofEvery(it, "site") }

    /** The predictions of the site's works [ids] gone from the index of the list, as of works never predicted. */
    private fun forget(ids: Collection<String>) {
        lists.of("books").written { db ->
            db.update(ITEM)
                .setNull(ITEM.PREDICTION)
                .setNull(ITEM.MODEL_INTEREST)
                .setNull(ITEM.EXPECTED)
                .where(ITEM.ITEM_KEY.`in`(ids.map { ItemKey("site", it).toString() }))
                .execute()
        }
    }

    /** Every work of the [scope] whose expectation is to be made made, a few at a time. */
    private fun allOf(scope: WorkScope) {
        var cursor: TaskCursor? = TaskCursor.FIRST
        while (cursor != null) cursor = workPredictions.turn("books", scope, cursor, PORTION)
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
     * Every work scored again by the current model, the pass begun anew and run to its end in one turn: inside a task
     * of the queue, on its thread.
     */
    private fun inOneGo() {
        workPredictions.begin(type, "asked")
        workPredictions.predictNow("books")
    }

    private companion object {
        const val CLOSE = 1e-4

        /** The works of a turn of the pass in turns. */
        const val PORTION = 7
    }
}
