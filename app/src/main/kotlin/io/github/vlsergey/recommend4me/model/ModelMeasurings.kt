package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * A measuring of a model asked for: under way until [finishedAt]; its [result], none when there were too few grades or
 * it failed.
 */
class Measuring<T : Any>(val startedAt: Instant) {
    @Volatile var result: T? = null
    @Volatile var finishedAt: Instant? = null
}

/**
 * THE MEASURINGS OF A MODEL ASKED FOR — what every part of the input is worth ([InputMeasures.worth]), whether weighing
 * the parts would rank better ([InputMeasures.weighing]) — IN THE BACKGROUND: each takes from a dozen trainings to
 * hundreds, planned in the queue of the computations, and the request that asks does not wait for them
 * ([io.github.vlsergey.recommend4me.task.Requests]). Nothing is kept but the measuring of every type last started,
 * while the application runs: it is a measuring, not data. A measuring under way is not started again.
 */
@Component
class ModelMeasurings(private val measures: InputMeasures, private val tasks: Tasks) {
    private val worth = ConcurrentHashMap<String, Measuring<ModelWorth>>()
    private val weighing = ConcurrentHashMap<String, Measuring<Weighing>>()

    fun worthOf(typeId: String): Measuring<ModelWorth>? = worth[typeId]

    fun weighingOf(typeId: String): Measuring<Weighing>? = weighing[typeId]

    fun measureWorth(typeId: String): Measuring<ModelWorth> =
        start(worth, Task(TaskKind.MEASURE_PART_WORTH, type = typeId)) { measures.worth(typeId) }

    fun measureWeighing(typeId: String, cautious: Boolean): Measuring<Weighing> =
        start(weighing, Task(TaskKind.WEIGH_PARTS, type = typeId)) { measures.weighing(typeId, cautious) }

    /**
     * The measuring [work] of [body] planned at once, the user's; its failure told by the queue, the measuring ended.
     */
    private fun <T : Any> start(of: ConcurrentHashMap<String, Measuring<T>>, work: Task, body: () -> T?): Measuring<T> =
        of.compute(work.type!!) { _, running ->
            if (running != null && running.finishedAt == null) return@compute running
            Measuring<T>(Instant.now()).also { m ->
                tasks.plan(work, null, "asked", asked = true) {
                    try {
                        m.result = body()
                    } finally {
                        m.finishedAt = Instant.now()
                    }
                }
            }
        }!!
}
