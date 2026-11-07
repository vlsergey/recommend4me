package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.ModelCandidate
import io.github.vlsergey.recommend4me.api.model.ModelMetrics
import io.github.vlsergey.recommend4me.api.model.ModelWorth as ApiModelWorth
import io.github.vlsergey.recommend4me.api.model.PartWorth as ApiPartWorth
import io.github.vlsergey.recommend4me.api.model.ScorerComparison as ApiScorerComparison
import io.github.vlsergey.recommend4me.api.model.ScorerQuality as ApiScorerQuality
import io.github.vlsergey.recommend4me.api.model.WeighedGroup as ApiWeighedGroup
import io.github.vlsergey.recommend4me.api.model.Weighing as ApiWeighing
import io.github.vlsergey.recommend4me.api.model.WeighingFold as ApiWeighingFold
import java.time.ZoneOffset

/*
 * The measures of the model of the grades — its quality, the comparison of the scorers, the worth of the parts of its
 * input, the weighing of them — as the web interface is told them.
 */

internal fun ScorerComparison.toApi() =
    ApiScorerComparison(
        startedAt = startedAt.atOffset(ZoneOffset.UTC),
        grades = grades,
        scorers =
            scorers.map { s ->
                ApiScorerQuality(
                    scorer = s.scorer,
                    state = ApiScorerQuality.State.valueOf(s.state.name),
                    candidates = s.candidates.map { it.toApi() },
                    error = s.error,
                    millis = s.millis,
                    chosen = s.chosen?.toApi(),
                )
            },
        finishedAt = finishedAt?.atOffset(ZoneOffset.UTC),
    )

internal fun RankingResult.toApi() = ModelCandidate(parameter, metrics.toApi())

internal fun RankingMetrics.toApi() =
    ModelMetrics(
        folds = folds,
        concordance = concordance,
        concordanceSe = concordanceSe,
        spearman = spearman,
        auc = auc,
    )

internal fun ModelWorth.toApi() =
    ApiModelWorth(
        scorer = scorer,
        grades = grades,
        parts = parts.map { ApiPartWorth(it.part, it.labelKey, it.features, it.metrics?.toApi()) },
        full = full?.toApi(),
    )

/** The weighing with the means of its folds' qualities and of its gain, each with its standard error. */
internal fun Weighing.toApi(): ApiWeighing {
    val (baseline, baselineSe) = GroupWeights.meanAndError(folds.map { it.baseline })
    val (weighed, weighedSe) = GroupWeights.meanAndError(folds.map { it.weighed })
    val (gain, gainSe) = GroupWeights.meanAndError(folds.map { it.weighed - it.baseline })
    return ApiWeighing(
        scorer = scorer,
        grades = grades,
        groups = groups.map { ApiWeighedGroup(it.part, it.labelKey, it.columns, it.weight) },
        folds = folds.map { f -> ApiWeighingFold(f.baseline, f.weighed, f.weights.toList()) },
        baseline = baseline,
        baselineSe = baselineSe,
        weighed = weighed,
        weighedSe = weighedSe,
        gain = gain,
        gainSe = gainSe,
        onAll = onAll?.toApi(),
    )
}
