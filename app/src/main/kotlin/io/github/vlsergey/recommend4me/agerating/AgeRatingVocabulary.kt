package io.github.vlsergey.recommend4me.agerating

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.suggestion.Vocabulary

/**
 * THE STEPS OF THE AGE RATING A WORK MAY HAVE: those its site lets it have ([AgeRating.SITE_KEY]) — every step of a
 * site that tells nothing. The age rating is a field of the chain as any other (owner, 2026-11-19: "this model is built
 * not of the tags but of the whole description of the work, as the other models are — it just comes after the tags";
 * "it must be done within the work making the models of every normalized field of the marked works"): its model learns
 * of the user's answers alone, the site's word an input, never a label — and offers a work only the steps its site
 * lets.
 */
internal object AgeRatingVocabulary {
    private val NAMES = AgeRating.VALUES.associate { it.key to it.modelName }

    /**
     * The vocabulary of the [facet] — the age rating — of the works [ids]; [site]: their site's values read already.
     * Null of any other facet.
     */
    fun of(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>,
        site: Map<String, Map<String, List<String>>>?,
    ): Vocabulary? {
        if (facet.key != AgeRating.KEY) return null
        val values = site ?: store.items.facetsOf(ids)
        val lets = ids.associateWith { values[it]?.get(AgeRating.SITE_KEY).orEmpty().toSet() }
        return Vocabulary(NAMES, allowed = { id -> lets[id]?.takeIf { it.isNotEmpty() } })
    }
}
