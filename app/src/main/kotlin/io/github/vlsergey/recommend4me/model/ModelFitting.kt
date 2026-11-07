package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.scorer.Scorer
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

private val log = LoggerFactory.getLogger(ModelFitting::class.java)

/**
 * THE FITTING OF THE MODELS OF A CONTENT TYPE ON ITS CATALOGUE — the model of the grades and the model of interest —
 * the scorer's parameter chosen by the cross-validation over works, and what is fitted kept: the models, and what the
 * models that had not seen the graded works say of them.
 */
@Component
class ModelFitting(private val plugins: Plugins, private val json: JsonMapper) {

    /** The model of the grades fitted on the [catalogue] and kept; null without a scorer, or with too few grades. */
    fun grades(catalogue: Catalogue): TrainedModel? {
        val began = System.currentTimeMillis()
        val type = catalogue.type
        val scorer = plugins.scorer(type.id)
        val p = if (scorer == null) null else Prepared.grades(catalogue)
        return when {
            scorer == null -> null.also { log.warn("{}: no scorer plugin: the works are not ranked", type.id) }
            p == null -> null.also { type.models.forget() }
            else -> grades(catalogue, scorer, p, began)
        }
    }

    /** The model of the grades fitted by [scorer] on the rows [p] of the [catalogue], and kept. */
    private fun grades(catalogue: Catalogue, scorer: Scorer, p: Prepared, began: Long): TrainedModel {
        val type = catalogue.type
        val started = System.currentTimeMillis()
        val candidates =
            if (p.perItem.size >= CrossValidation.MIN_WORKS) CrossValidation.measureAll(p.task, scorer, p.xFor)
            else emptyList()
        val chosen = CrossValidation.choose(candidates)
        val parameter = chosen?.parameter ?: scorer.defaultParameter
        log.info(
            "{}: {} values of the {} parameter measured on {} grades × {} features in {} ms, chosen {}",
            type.id,
            candidates.size,
            scorer.id,
            p.used.size,
            p.layout.width,
            System.currentTimeMillis() - started,
            parameter,
        )
        val (fitted, scale) = scaled(scorer, p, parameter, chosen)
        val provisional = metaOf(catalogue, scorer, p, chosen, candidates, scale)
        val importance = importance(TrainedModel(provisional, fitted), p.perItem)
        val meta =
            provisional.copy(
                topPositive = importance.filter { it.contribution > 0 }.take(TOP_FEATURES),
                topNegative = importance.filter { it.contribution < 0 }.reversed().take(TOP_FEATURES),
                trainMillis = System.currentTimeMillis() - began,
            )
        type.models.save(
            scorer.id,
            Instant.ofEpochMilli(meta.trainedAtMillis),
            json.writeValueAsString(meta),
            fitted.pack(),
        )
        // Of a work, every item of it: each shows the work's
        type.heldOut.replace(
            heldOutScores(p, chosen, scale)
                .flatMap { (key, score) -> catalogue.works.members(key).map { it to score } }
                .toMap()
        )
        return TrainedModel(meta, fitted)
    }

    /**
     * The description of the model fitted with the [chosen] parameter on the rows [p], its strongest features not yet.
     */
    private fun metaOf(
        catalogue: Catalogue,
        scorer: Scorer,
        p: Prepared,
        chosen: RankingCandidate?,
        candidates: List<RankingCandidate>,
        scale: Scale,
    ) =
        ModelMeta(
            scorer = scorer.id,
            parameter = chosen?.parameter ?: scorer.defaultParameter,
            categorical = p.layout.categorical,
            categoricalShare = categoricalShare(p.layout, catalogue),
            vectorCenters = p.spreads.mapValues { it.value.center },
            vectorSpreads = p.spreads.mapValues { it.value.spread },
            numbers = p.numbers,
            scoreMean = scale.mean,
            scoreSd = scale.sd,
            ladder = scale.ladder,
            trainedAtMillis = System.currentTimeMillis(),
            ratingCounts = gradeCounts(p.grades),
            metrics = chosen?.metrics,
            candidates = candidates.map { it.result() },
            topPositive = emptyList(),
            topNegative = emptyList(),
            textEncoder = plugins.textEncoder()?.id,
            imageEncoder = plugins.imageEncoder()?.id,
        )

    /**
     * THE MODEL OF INTEREST: the type's scorer learns to put the works the user found interesting above the ones not,
     * on the same features as the grades, its parameter chosen by the same cross-validation over works; Platt's scaling
     * of the out-of-fold scores makes its score a chance. None — and no chances — while there are too few works of
     * either kind for folds.
     */
    fun interest(catalogue: Catalogue): TrainedInterest? {
        val began = System.currentTimeMillis()
        val type = catalogue.type
        val scorer = plugins.interestScorer(type.id) ?: return null
        val p = Prepared.interest(catalogue)
        val measured =
            p?.takeIf { it.perItem.size >= CrossValidation.MIN_WORKS }
                ?.let { CrossValidation.measureAll(it.task, scorer, it.xFor) }
                .orEmpty()
        val chosen = CrossValidation.choose(measured)
        val positive = p?.let { BooleanArray(it.grades.size) { i -> it.grades[i] == Prepared.INTERESTING } }
        val calibration = chosen?.let { Calibration.fit(it.scores, positive!!) }
        return if (p == null || chosen == null || calibration == null) {
            log.info("{}: too few works marked of interest for folds: no chances of interest", type.id)
            type.models.forgetInterest()
            null
        } else interest(catalogue, scorer, p, chosen, measured, calibration, positive!!, began)
    }

    /**
     * The model of interest fitted by [scorer] on the rows [p] with the [chosen] parameter of those [measured], its
     * score made a chance by the [calibration]; kept.
     */
    private fun interest(
        catalogue: Catalogue,
        scorer: Scorer,
        p: Prepared,
        chosen: RankingCandidate,
        measured: List<RankingCandidate>,
        calibration: Calibration,
        positive: BooleanArray,
        began: Long,
    ): TrainedInterest {
        val type = catalogue.type
        val fitted = scorer.fit(p.task, p.everyRow, chosen.parameter)
        val (mean, sd) = CrossValidation.standardisation(fitted.scores(p.x, p.grades.size).map { it.toDouble() })
        val meta =
            InterestMeta(
                scorer = scorer.id,
                parameter = chosen.parameter,
                categorical = p.layout.categorical,
                vectorCenters = p.spreads.mapValues { it.value.center },
                vectorSpreads = p.spreads.mapValues { it.value.spread },
                numbers = p.numbers,
                scoreMean = mean,
                scoreSd = sd,
                calibration = calibration,
                trainedAtMillis = System.currentTimeMillis(),
                interesting = positive.count { it },
                notInteresting = positive.count { !it },
                metrics = chosen.metrics,
                candidates = measured.map { it.result() },
                trainMillis = System.currentTimeMillis() - began,
            )
        log.info(
            "{}: the model of interest learnt {} interesting and {} not, out-of-fold AUC {}, parameter {}",
            type.id,
            meta.interesting,
            meta.notInteresting,
            "%.3f".format(chosen.metrics.concordance),
            chosen.parameter,
        )
        type.models.saveInterest(
            scorer.id,
            Instant.ofEpochMilli(meta.trainedAtMillis),
            json.writeValueAsString(meta),
            fitted.pack(),
        )
        return TrainedInterest(meta, fitted)
    }

    /**
     * The scorer fitted on all grades with [parameter], and its scale: the ladder from [measured]'s out-of-fold scores.
     */
    private fun scaled(
        scorer: Scorer,
        p: Prepared,
        parameter: Double,
        measured: RankingCandidate?,
    ): Pair<FittedScorer, Scale> {
        val fitted = scorer.fit(p.task, p.everyRow, parameter)
        val own = fitted.scores(p.x, p.grades.size).map { it.toDouble() }
        val (mean, sd) = CrossValidation.standardisation(own)
        // Too few works for folds: the ladder of the training scores, too wide but the only one there is
        val scores = measured?.scores ?: DoubleArray(own.size) { (own[it] - mean) / sd }
        return fitted to Scale(mean, sd, CrossValidation.ladder(p.grades, scores))
    }

    /**
     * The prediction of every graded item by a model that had not seen its work: the score the cross-validation of the
     * chosen parameter gave its latest grade's row from the fold the work was left out of, on the model's scale. None
     * when there were too few works for folds.
     */
    private fun heldOutScores(p: Prepared, chosen: RankingCandidate?, scale: Scale): Map<ItemKey, Double> {
        val scores = chosen?.scores ?: return emptyMap()
        return p.used.indices
            .groupBy { p.used[it].key }
            .mapValues { (_, rows) -> scale.ofStandard(scores[rows.maxBy { p.used[it].ratedAt }]) }
    }

    /**
     * What a group of features adds to a work's score: the score with it minus the score with it switched off, in
     * points.
     */
    private fun occlusion(model: TrainedModel, input: ItemInput): List<Pair<String, Double>> {
        val groups = model.layout.groups(input)
        val m = Matrix(groups.size + 1, model.layout.width)
        model.layout.write(input, m, 0)
        groups.forEachIndexed { g, group -> model.layout.write(input, m, g + 1, group) }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        return groups.mapIndexed { g, group -> group.feature to full - model.scale.score(raw[g + 1].toDouble()) }
    }

    /**
     * Mean contribution of every feature over the graded works that have it, strongest positive first: the works'
     * occlusions side by side, summed in their order.
     */
    private fun importance(model: TrainedModel, perItem: List<ItemInput>): List<Contribution> {
        val sums = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        val occluded = perItem.take(MAX_IMPORTANCE_ITEMS).parallelStream().map { occlusion(model, it) }.toList()
        occluded.forEach { contributions ->
            contributions.forEach { (feature, c) ->
                sums.merge(feature, c, Double::plus)
                counts.merge(feature, 1, Int::plus)
            }
        }
        val means =
            sums.entries
                .filter { (counts[it.key] ?: 0) >= FeatureLayout.MIN_SUPPORT }
                .map { it.key to it.value / counts.getValue(it.key) }
        val ranked = means.sortedByDescending { it.second }
        val shown = (ranked.take(TOP_FEATURES) + ranked.takeLast(TOP_FEATURES)).map { it.first }.toSet()
        return ranked.filter { it.first in shown }.map { (f, c) -> Contribution(f, c) }
    }

    private companion object {
        const val TOP_FEATURES = 25
        const val MAX_IMPORTANCE_ITEMS = 1500
    }
}
