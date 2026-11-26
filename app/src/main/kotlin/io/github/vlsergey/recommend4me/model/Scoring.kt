package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListPredictions
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * THE SCORE OF EVERY ITEM BY THE CURRENT MODELS — of 0..10 by the model of the grades, the chance of interest by the
 * model of interest — of a work, written to every item of it; and the input of one work as the model reads it now.
 */
@Component
class Scoring(
    private val stores: Stores,
    private val reader: CatalogueReader,
    private val works: Works,
    private val likenesses: Likenesses,
    private val kept: KeptModels,
    private val indexes: ListIndexes,
    private val tasks: Tasks,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WRITE) { left, reason ->
            left.work.item?.let { planWrite(ItemKey(left.work.source!!, it), reason) }
        }
    }

    /**
     * The works of the [catalogue] — a portion of every work ([EveryWork.portions]) — scored by the [model] and the
     * model of [interest], each work's prediction written to every item of it in the index of the list where it changed
     * as the index keeps it ([ListPredictions.write]), not committed: their vectors read a chunk at a time and let go
     * after it. Answers how many items were scored, and of how many the prediction changed.
     */
    fun score(catalogue: Catalogue, model: TrainedModel, interest: TrainedInterest?): PortionScored {
        val predicted = scored(catalogue, model, interest)
        val written = ListPredictions.write(indexes.of(catalogue.type.id), catalogue.type, predicted)
        return PortionScored(predicted.size, written.size)
    }

    /** The prediction of every item of the works of the [catalogue], a chunk of them at a time side by side. */
    private fun scored(
        catalogue: Catalogue,
        model: TrainedModel,
        interest: TrainedInterest?,
    ): List<Pair<ItemKey, Predicted>> {
        val previousForCurrent =
            CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        return catalogue.items
            .chunked(SCORE_CHUNK)
            .parallelStream()
            .flatMap { chunk ->
                val vectors = catalogue.vectorsOfMany(chunk.map { it.key })
                val inputs = chunk.map {
                    CatalogueReader.inputOf(it, vectors.getValue(it.key), previousForCurrent[it.key])
                }
                val raw = model.scores(model.layout.matrix(inputs))
                val chances = interest?.chances(inputs)
                // Of a work, every item of it: each shows the work's
                chunk.indices
                    .flatMap { i ->
                        val prediction = Predicted(model.scale.score(raw[i].toDouble()), chances?.get(i))
                        catalogue.works.members(chunk[i].key).map { it to prediction }
                    }
                    .stream()
            }
            .toList()
    }

    /**
     * The work of one item scored by the models kept, after its data changed outside a training — of a work the user
     * opens, as its page is read ([read]: its items and chances read already): the models untouched; its prediction, of
     * every item of the work — what its screen shows now —, written to the index of the list by the queue after
     * ([planWrite]): a request writes no index. None of no model.
     */
    fun scoreItem(key: ItemKey, read: ReadWork? = null): Map<ItemKey, Predicted> {
        val made = predictionOf(key, read) ?: return emptyMap()
        planWrite(key, "predicted")
        return made
    }

    /** The work of one item scored by the models kept: of every item of it, its prediction; none of no model. */
    private fun predictionOf(key: ItemKey, read: ReadWork?): Map<ItemKey, Predicted>? {
        val type = stores.typeOf(key.source)
        val (model, input) =
            kept.load(type)?.let { m -> inputOf(type, key, likenesses.stored(type), read)?.let { m to it } }
                ?: return null
        val prediction = Predicted(model.score(input), kept.loadInterest(type)?.chances(listOf(input))?.single())
        val members = read?.members ?: works.ofKeys(type, listOf(key)).members(key)
        return members.associateWith { prediction }
    }

    /**
     * The prediction of the work of [key] made again and written to the index of the list, in the queue — the work the
     * user opened, its prediction shown at once —, for the [reason].
     */
    private fun planWrite(key: ItemKey, reason: String) {
        val type = stores.typeOf(key.source)
        tasks.plan(Task(WRITE, type = type.id, source = key.source, item = key.id), Instant.now(), reason) {
            val made = predictionOf(key, null) ?: return@plan
            val index = indexes.of(type.id)
            ListPredictions.write(index, type, made.toList())
            index.commit()
        }
    }

    /** The input of the work of one item as the model reads it now; [stored]: the type's likeness to the marks. */
    fun inputOf(
        type: TypeStore,
        key: ItemKey,
        stored: TypeLikeness,
        /** The work read already: its items and their chances, not read again. */
        read: ReadWork? = null,
    ): ItemInput? {
        val item = reader.work(type, key, read) ?: return null
        val ratings =
            type.source(key.source)!!.ratings.ofItem(key.id).map {
                TypedRating(item.key, it.version, it.grade, it.ratedAt)
            }
        val previous = CatalogueReader.previousGradesOfCurrentVersions(ratings) { item.version }[item.key]
        val members = read?.members ?: works.ofKeys(type, listOf(key)).members(key)
        val likeness = members.map { likenesses.of(type, it, stored) }.filter { it.isNotEmpty() }
        return CatalogueReader.inputOf(
            item,
            reader.vectors(type, key, members) + WorkFeatures.vectors(likeness),
            previous,
        )
    }

    private companion object {
        const val SCORE_CHUNK = 1024

        /** The task of the queue writing the prediction of a work the user opened. */
        val WRITE = TaskKind.WRITE_WORK_VALUES
    }
}

/** Of a portion scored ([Scoring.score]): how many items, of how many the prediction changed. */
class PortionScored(val items: Int, val changed: Int)
