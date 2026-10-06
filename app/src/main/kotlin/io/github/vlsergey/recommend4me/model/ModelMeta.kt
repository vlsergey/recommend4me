package io.github.vlsergey.recommend4me.model

/**
 * What a feature adds to a work's score, in points of 0..10; for a categorical feature against the
 * average work of the catalogue — so a liked tag the work does NOT have ([present] false) shows as
 * what its absence costs.
 */
data class Contribution(val feature: String, val label: String, val contribution: Double, val present: Boolean = true)

/** Everything about a trained model but its scorer's state; kept as JSON beside it. */
data class ModelMeta(
    val scorer: String,
    /** The scorer's parameter, chosen by cross-validation. */
    val parameter: Double,
    val categorical: List<String>,
    /** Share of the catalogue's items with every [categorical] feature: the average work the explanation compares with. */
    val categoricalShare: FloatArray,
    /** Centre and spread of every vector block the model was trained with. */
    val vectorCenters: Map<String, FloatArray>,
    val vectorSpreads: Map<String, Float>,
    val numbers: Map<String, NumberSpread>,
    /** How the scorer's scores become 0..10: see [Scale]. */
    val scoreMean: Double,
    val scoreSd: Double,
    val ladder: Map<Int, Double>,
    val trainedAtMillis: Long,
    /** How many grades of every value, 1..5, the model was trained on. */
    val ratingCounts: IntArray,
    val metrics: RankingMetrics?,
    val candidates: List<RankingResult>,
    val topPositive: List<Contribution>,
    val topNegative: List<Contribution>,
    val textEncoder: String?,
    val imageEncoder: String?,
) {
    fun scale() = Scale(scoreMean, scoreSd, ladder)

    fun layout() = FeatureLayout(
        categorical,
        vectorCenters.mapValues { (key, center) -> VectorSpread(center, vectorSpreads.getValue(key)) },
        numbers,
    )
}

/** What the interface shows about the model of a content type. */
data class ModelSummary(
    val meta: ModelMeta?,
    val training: Boolean,
    val stale: Boolean,
    val textEncoderReady: Boolean,
    val imageEncoderReady: Boolean,
    val scorer: String?,
    /** How many grades of every value, 1..5, there are now. */
    val ratingCounts: IntArray,
)

/** A scorer's quality on the current grades. */
class ScorerQuality(val scorer: String, val title: String, val chosen: RankingResult?, val candidates: List<RankingResult>)
