package io.github.vlsergey.recommend4me.agerating

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.normalizedTags

/**
 * WHAT IS KNOWN OF THE AGE RATINGS OF A SOURCE'S WORKS, AND WHAT THE MODEL READS OF THEM: the ratings a site lets a
 * work have, the one it states, the user's answers ([Told]); the steps of the scale a rating is known to lie between
 * ([spanOf]); a work's features ([featuresOf]). Of the steps [keys] of the scale, from the mildest.
 */
internal class RatingEvidence(private val keys: List<String>) {

    /**
     * What is known of a work's rating: the ratings its site lets it have, its own the site states, the user's answers
     * on it.
     */
    class Told(val allowed: List<String>, val stated: List<String>, val answers: List<FacetCorrection>)

    /**
     * The user's answers on the works [ids] of the source — every work when null: read once, for [told] and
     * [featuresOf] both.
     */
    fun answersOf(s: SourceStore, ids: Collection<String>?): List<FacetCorrection> =
        if (ids == null) s.corrections.allFacets() else s.corrections.facetsOf(ids).values.flatten()

    /**
     * What is known of the ratings of the works [ids] of the source — every work when null — by item; [corrections]:
     * the user's answers on them; [site]: of every work, the site's values read already.
     */
    fun told(
        s: SourceStore,
        ids: Collection<String>?,
        corrections: List<FacetCorrection>,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Map<String, Told> {
        val facets =
            site
                ?: if (ids == null)
                    HashMap<String, Map<String, List<String>>>().also { out ->
                        listOf(AgeRating.SITE_KEY, AgeRating.KEY).forEach { f ->
                            s.items.forEachFacet(f) { id, keys -> out[id] = out[id].orEmpty() + (f to keys) }
                        }
                    }
                else s.items.facetsOf(ids)
        val answers = corrections.filter { it.facet == AgeRating.KEY }.groupBy { it.itemId }
        val all = ids ?: s.items.keys().map { it.id }
        return all.associateWith { id ->
            val own = facets[id].orEmpty()
            Told(own[AgeRating.SITE_KEY].orEmpty(), own[AgeRating.KEY].orEmpty(), answers[id].orEmpty())
        }
    }

    /**
     * What the model reads of the works [ids] of the source — every work when null: the site it is of (a site's works
     * may all be stronger or milder whatever their tags: every game of F95zone is for adults), and its normalized tags
     * — the site's tags bound to them, the user's word on the layer's tags over them; no tag the model works out.
     * [corrections]: the user's answers on them; [site]: of every work, the site's values read already.
     */
    fun featuresOf(
        s: SourceStore,
        ids: Collection<String>?,
        corrections: List<FacetCorrection>,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Map<String, List<String>> {
        val tags = s.normalizedTags(ids, corrections, site)
        val source = AgeRatingRepository.source(s.id)
        return (ids ?: s.items.keys().map { it.id }).associateWith { id ->
            listOf(source) + tags[id].orEmpty().map(AgeRatingRepository::tag)
        }
    }

    /**
     * The steps of the scale a work's rating is known to lie between: the user's yes, the rating the site states, the
     * bound it tells; null of a work nothing is known of, or of one any rating may be.
     */
    fun spanOf(t: Told): IntRange? {
        val known =
            t.answers.firstOrNull { it.added }?.key?.let(::listOf) ?: t.stated.takeIf { it.isNotEmpty() } ?: t.allowed
        val steps = known.map { keys.indexOf(it) }.filter { it >= 0 }
        return if (steps.isEmpty()) null else (steps.min()..steps.max()).takeIf { it != keys.indices }
    }

    /** Every work of the source [s] whose rating is known to lie between two steps, as the model learns from it. */
    fun examples(s: SourceStore): List<RatingExample> {
        val answers = answersOf(s, null)
        val features = featuresOf(s, null, answers)
        return told(s, null, answers).mapNotNull { (id, t) ->
            spanOf(t)?.let { span -> RatingExample(features[id].orEmpty(), span.first, span.last) }
        }
    }
}
