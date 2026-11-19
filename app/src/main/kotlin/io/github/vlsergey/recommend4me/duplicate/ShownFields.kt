package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.api.model.SizeNumber

/**
 * A work as a pair shows it: its head ([work]), its normalized authors — what "the same author" is told by — and its
 * fields ([fields]), what the rows of a pair are made of ([PairFields]).
 */
internal class Described(val work: DuplicateWork, val authors: Set<Long>, val fields: ShownFields)

/**
 * THE FIELDS OF A WORK AS A PAIR SHOWS THEM: the names of its authors and of its site's fandoms, its description and
 * the site's own line of its characters and pairings as the user corrected them, the site's own tags (none of a site
 * without tags), the values of every facet a pair is compared by ([facets]: as the search compares them — the site's,
 * the drafts, the model's kept above its cutoff, the user's answers over them, the ignored tags left out —, or in the
 * window of one pair as the work's screen gives them), its size in words and in characters.
 */
internal class ShownFields(
    val authors: List<String>,
    val fandoms: List<String>,
    val description: String?,
    val universeLine: String?,
    val siteTags: List<DuplicateValue>?,
    val facets: List<ShownFacet>,
    val words: SizeNumber?,
    val chars: SizeNumber?,
) {
    /** The same fields with the values of the facets [facets]. */
    fun withFacets(facets: List<ShownFacet>) =
        ShownFields(authors, fandoms, description, universeLine, siteTags, facets, words, chars)
}

/**
 * The values of a work of one facet it is compared by ([key]: the facet of the layer — "characters" of the main and the
 * secondary alike — or the standard one, "status"), named by [labelKey]. [allowed]: the values a work may have one of
 * (the ratings a site lets); [single]: a work has one at most (the status, the kind), two works compared by whether it
 * is the same; [computed]: as the work's screen gives them — its chances made now —, of the window of one pair alone,
 * compared by what they share; [compared]: the search compares works by it — false of a facet of the layer a work's
 * screen gives that the search compares not.
 */
internal class ShownFacet(
    val key: String,
    val labelKey: String,
    val values: List<DuplicateValue>,
    val allowed: Boolean = false,
    val single: Boolean = false,
    val computed: Boolean = false,
    val compared: Boolean = true,
)
