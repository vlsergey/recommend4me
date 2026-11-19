package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.context.ApplicationEventPublisher
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
    private val events: ApplicationEventPublisher,
) {

    /**
     * The works of the [catalogue] — a portion of every work ([EveryWork.portions]) — scored by the [model] and the
     * model of [interest], each work's prediction written to every item of it where it changed
     * ([PredictionRepository.write]) and told: their vectors read a chunk at a time and let go after it. Answers how
     * many items were scored, and of how many the prediction changed.
     */
    fun score(catalogue: Catalogue, model: TrainedModel, interest: TrainedInterest?): PortionScored {
        val predicted = scored(catalogue, model, interest)
        val written = catalogue.type.predictions.write(predicted)
        if (written.isNotEmpty()) events.publishEvent(WorkPredictionChangeEvent(catalogue.type.id, written))
        return PortionScored(predicted.size, written.size)
    }

    /**
     * The predictions of the items no longer of the [type] removed — of a source gone, of an item gone from its source:
     * the predictions looked over a [PAGE] at a time, each page's ids asked of their source. Answers how many.
     */
    fun forgetGone(type: TypeStore): Int {
        var gone = 0
        type.predictions.sources().forEach { source ->
            val s = type.source(source)
            var after: String? = null
            do {
                val ids = type.predictions.idsAfter(source, after, PAGE)
                after = ids.lastOrNull()
                val left = s?.items?.keysOf(ids)?.mapTo(HashSet()) { it.id }.orEmpty()
                val removed = ids.filter { it !in left }
                if (removed.isNotEmpty()) {
                    type.predictions.remove(source, removed)
                    events.publishEvent(WorkPredictionChangeEvent(type.id, removed.map { ItemKey(source, it) }))
                    gone += removed.size
                }
            } while (ids.size == PAGE)
        }
        return gone
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

    /** Re-scores the work of one item, after its data changed outside a training. */
    fun scoreItem(key: ItemKey) {
        val type = stores.typeOf(key.source)
        val model = kept.load(type) ?: return
        val input = inputOf(type, key, likenesses.stored(type)) ?: return
        val prediction = Predicted(model.score(input), kept.loadInterest(type)?.chances(listOf(input))?.single())
        val members = works.ofKeys(type, listOf(key)).members(key)
        members.forEach { type.predictions.save(it, prediction) }
        events.publishEvent(WorkPredictionChangeEvent(type.id, members))
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

        /** The predictions looked over at a time for the items gone: the size of a query, not meaning. */
        const val PAGE = 1000
    }
}

/** Of a portion scored ([Scoring.score]): how many items, and of how many the prediction changed. */
class PortionScored(val items: Int, val changed: Int)
