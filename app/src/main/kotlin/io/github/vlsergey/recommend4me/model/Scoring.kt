package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Scoring::class.java)

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
) {

    /** Every item of the [catalogue] scored by the [model] and the model of [interest], and the scores kept. */
    fun scoreAll(catalogue: Catalogue, model: TrainedModel, interest: TrainedInterest?) {
        val previousForCurrent =
            CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        // Every work's vectors read with its chunk and let go after it: of the catalogue only the training's are kept
        val predicted =
            catalogue.items
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
        val written = System.currentTimeMillis()
        catalogue.type.predictions.replace(predicted)
        log.info(
            "{}: {} predictions written in {} ms",
            catalogue.type.id,
            predicted.size,
            System.currentTimeMillis() - written,
        )
    }

    /** Re-scores the work of one item, after its data changed outside a training. */
    fun scoreItem(key: ItemKey) {
        val type = stores.typeOf(key.source)
        val model = kept.load(type) ?: return
        val input = inputOf(type, key, likenesses.stored(type)) ?: return
        val prediction = Predicted(model.score(input), kept.loadInterest(type)?.chances(listOf(input))?.single())
        works.of(type).members(key).forEach { type.predictions.save(it, prediction) }
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
        val members = read?.members ?: works.of(type).members(key)
        val likeness = members.map { likenesses.of(type, it, stored) }.filter { it.isNotEmpty() }
        return CatalogueReader.inputOf(
            item,
            reader.vectors(type, key, members) + WorkFeatures.vectors(likeness),
            previous,
        )
    }

    private companion object {
        const val SCORE_CHUNK = 1024
    }
}
