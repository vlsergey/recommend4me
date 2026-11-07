package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.source.TypeStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

private val log = LoggerFactory.getLogger(KeptModels::class.java)

/** A trained model as kept: its description and its scorer, read for one operation. */
class TrainedModel(val meta: ModelMeta, val scorer: FittedScorer) {
    val layout = meta.layout()
    val scale = meta.scale()

    fun scores(m: Matrix): FloatArray = scorer.scores(m)

    fun score(input: ItemInput): Double = scale.score(scorer.scores(layout.matrix(listOf(input)))[0].toDouble())
}

/** A trained model of interest as kept, read for one operation. */
class TrainedInterest(val meta: InterestMeta, val scorer: FittedScorer) {
    val layout = meta.layout()

    /** The chance of interest of every one of [inputs], 0..1. */
    fun chances(inputs: List<ItemInput>): List<Double> =
        scorer.scores(layout.matrix(inputs)).map { meta.chance(it.toDouble()) }
}

/**
 * THE MODELS OF A CONTENT TYPE AS KEPT IN ITS MODEL DATABASE, read when an operation needs them — nothing is kept in
 * memory between operations; a model that cannot be read is as none, and is trained again.
 */
@Component
class KeptModels(private val plugins: Plugins, private val json: JsonMapper) {

    /** The current model of a type, read now; null without one, or when it cannot be read. */
    fun load(type: TypeStore): TrainedModel? =
        // A stored model is unpacked by its scorer plugin: whatever the plugin's reading of bytes it once wrote
        // throws, the model is taken as missing and trained again
        @Suppress("TooGenericExceptionCaught")
        try {
            type.models.load()?.let { stored ->
                val scorer = plugins.scorers.firstOrNull { it.id == stored.scorer } ?: return null
                TrainedModel(json.readValue<ModelMeta>(stored.meta), scorer.unpack(stored.weights))
            }
        } catch (e: Exception) {
            log.warn("{}: the stored model cannot be read and will be retrained: {}", type.id, e.message)
            null
        }

    /** The current model of interest of a type, read now; null without one, or when it cannot be read. */
    fun loadInterest(type: TypeStore): TrainedInterest? =
        // As the model of the grades: whatever its plugin's unpacking throws, the model is taken as missing
        @Suppress("TooGenericExceptionCaught")
        try {
            type.models.loadInterest()?.let { stored ->
                val scorer = plugins.scorers.firstOrNull { it.id == stored.scorer } ?: return null
                TrainedInterest(json.readValue<InterestMeta>(stored.meta), scorer.unpack(stored.weights))
            }
        } catch (e: Exception) {
            log.warn("{}: the stored model of interest cannot be read and will be retrained: {}", type.id, e.message)
            null
        }
}
