package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

private val log = LoggerFactory.getLogger(HeldOutModels::class.java)

/** What a model that had not seen a graded work says of it: its score, where the user's grade stands, and why. */
class HeldOut(val score: Double, val gradePlace: Double?, val explanation: List<ContributionGroup>)

/**
 * What a request reads of the model that had not seen a graded work: what it says, nothing to say, or being worked out
 * — planned in the queue.
 */
sealed interface HeldOutRead {
    class Ready(val heldOut: HeldOut) : HeldOutRead

    data object None : HeldOutRead

    data object Planned : HeldOutRead
}

/**
 * HOW THE MODEL WOULD GRADE A GRADED WORK NOT KNOWING THE USER'S GRADE: the model of the fold the work was left out of
 * in the cross-validation — fitted again on every other fold with the model's parameter, the likeness to the marks of
 * the fold's works not counted — scores it and tells why. The model of every grade has seen the work and partly learnt
 * it by heart (its annotation, its cover); this one has not. The score is the one the list sorts the surprises by.
 */
@Component
class HeldOutModels(
    private val stores: Stores,
    private val plugins: Plugins,
    private val reader: CatalogueReader,
    private val works: Works,
    private val kept: KeptModels,
    private val explanations: Explanations,
    private val json: JsonMapper,
    private val workload: Workload,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "held-out").apply { isDaemon = true } }

    /**
     * What the model that had not seen the graded work says of it. None for a work the user has not graded, or when
     * there are too few works for folds.
     *
     * Read as kept: the fitting takes seconds, in the queue of the computations, and a request never waits for it
     * ([io.github.vlsergey.recommend4me.workload.Requests]) — not yet worked out, it is planned, and the request
     * answers so.
     */
    fun heldOut(key: ItemKey): HeldOutRead {
        val type = stores.typeOf(key.source)
        val graded = graded(type, key)
        val explained = if (graded) type.heldOut.explained(key) else null
        val score = explained?.score
        return when {
            !graded -> HeldOutRead.None
            explained == null -> HeldOutRead.Planned.also { planHeldOut(type, key) }
            score == null -> HeldOutRead.None
            else ->
                HeldOutRead.Ready(
                    HeldOut(
                        score,
                        explained.gradePlace,
                        explained.explanation?.let { json.readValue<List<ContributionGroup>>(it) }.orEmpty(),
                    )
                )
        }
    }

    /** A word on a graded work: what the model that had not seen it said is of the work as it was. */
    fun forget(source: String, itemId: String) = stores.typeOf(source).heldOut.forgetExplained(ItemKey(source, itemId))

    /** Whether the user graded the item [key]. */
    private fun graded(type: TypeStore, key: ItemKey): Boolean =
        !type.source(key.source)?.ratings?.ofItem(key.id).isNullOrEmpty()

    /**
     * The model that had not seen the graded work fitted in the queue of the computations — ahead of the background's
     * works: a page the user opened asked for it — and what it says kept ([HeldOutRepository.keepExplained]); the
     * request that asked does not wait for it. Asked again before it is worked out, it is worked out once.
     */
    private fun planHeldOut(type: TypeStore, key: ItemKey) {
        val work = Work("heldOut", source = key.source, item = key.id)
        workload.plan(work, Instant.now(), "page")
        worker.execute {
            // The boundary of the worker's thread: whatever a fitting throws is logged, the next one still runs
            @Suppress("TooGenericExceptionCaught")
            try {
                if (type.heldOut.explained(key) != null) {
                    workload.take(work)
                    return@execute
                }
                workload.run(work, asked = true) {
                    val h = fitHeldOut(key)
                    type.heldOut.keepExplained(
                        key,
                        h?.score,
                        h?.gradePlace,
                        h?.let { json.writeValueAsString(it.explanation) },
                    )
                }
            } catch (e: Exception) {
                log.warn("{}: what a model that had not seen the work says of it was not worked out", key, e)
            }
        }
    }

    /** The model of the work of [asked] fitted without its fold; null when it is not graded, or there is no model. */
    private fun fitHeldOut(asked: ItemKey): HeldOut? {
        val started = System.currentTimeMillis()
        val type = stores.typeOf(asked.source)
        val model = if (graded(type, asked)) kept.load(type) else null
        val scorer = model?.let { m -> plugins.scorers.firstOrNull { it.id == m.meta.scorer } }
        return if (model == null || scorer == null) null else fitHeldOut(type, asked, model, scorer, started)
    }

    /**
     * The model of the fold the work of the graded item [asked] fell in, fitted by [scorer] with the parameter of the
     * [model] of every grade on every other fold; null when there are too few works for folds.
     */
    private fun fitHeldOut(
        type: TypeStore,
        asked: ItemKey,
        model: TrainedModel,
        scorer: Scorer,
        started: Long,
    ): HeldOut? {
        val key = works.of(type).representative(asked)
        // The rows of the grades are all a fitting reads; the model kept tells what the average work is.
        // The work's own read with the graded ones': it is graded, but said so all the same
        val catalogue = reader.gradedWorks(type, model.meta.averages(), also = listOf(key))
        val read = System.currentTimeMillis()
        val p = Prepared.grades(catalogue)
        val own = p?.let { q -> q.used.indices.filter { q.used[it].key == key }.maxByOrNull { q.used[it].ratedAt } }
        val folds = p?.let { CrossValidation.folds(it.grades, it.works) }?.takeIf { f -> f.any { it != 0 } }
        return if (p == null || own == null || folds == null) null
        else
            foldOf(type, key, model, scorer, catalogue, p, own, folds).also {
                log.info(
                    "{}: what a model that had not seen it says worked out in {} ms: read {}, fitted {}",
                    asked,
                    System.currentTimeMillis() - started,
                    read - started,
                    System.currentTimeMillis() - read,
                )
            }
    }

    /** What the model of the fold of the row [own] of [p] says of the work [key], and why. */
    private fun foldOf(
        type: TypeStore,
        key: ItemKey,
        model: TrainedModel,
        scorer: Scorer,
        catalogue: Catalogue,
        p: Prepared,
        own: Int,
        folds: IntArray,
    ): HeldOut {
        val fold = folds[own]
        val underTest = p.grades.indices.filter { folds[it] == fold }.map { p.works[it] }.toSet()
        val train = p.grades.indices.filter { folds[it] != fold }.toIntArray()
        val x = p.xFor?.invoke(underTest) ?: p.x
        val fitted = scorer.fit(RankingTask(x, p.grades, p.works, p.items), train, model.meta.parameter)
        val all = fitted.scores(x).map { it.toDouble() }
        val (mean, sd) = CrossValidation.standardisation(train.map { all[it] })
        // Fitted on the catalogue as it is now, so laid out as it is now: an answer given since the
        // model was trained adds or drops a feature, and the kept model's layout no longer fits
        val foldModel =
            TrainedModel(
                model.meta.copy(
                    categorical = p.layout.categorical,
                    categoricalShare = categoricalShare(p.layout, catalogue),
                    vectorCenters = p.spreads.mapValues { it.value.center },
                    vectorSpreads = p.spreads.mapValues { it.value.spread },
                    numbers = p.numbers,
                    scoreMean = mean,
                    scoreSd = sd,
                ),
                fitted,
            )
        // The work as the fold saw it: its current input, its likeness to the marks of the fold's works left out
        val excluded = underTest.flatMap { p.keysOfWork[it].orEmpty() }.flatMap(catalogue.works::members).toSet()
        val current = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)[key]
        val input =
            CatalogueReader.inputOf(catalogue.byKey.getValue(key), catalogue.vectorsOf(key), current).let { i ->
                if (catalogue.likeness.empty) i
                else i.withVectors(i.vectors - MARK_KEYS + catalogue.likenessOf(key, excluded))
            }
        return HeldOut(
            score = foldModel.scale.ofStandard((all[own] - mean) / sd),
            gradePlace = model.scale.grades()[p.used[own].grade],
            explanation = explanations.explain(foldModel, input, type),
        )
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }
}
