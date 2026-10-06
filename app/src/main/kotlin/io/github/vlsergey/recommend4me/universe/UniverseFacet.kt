package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.FacetDef

/**
 * The facet the application gives every work of a content type with universes: the universes of
 * the dictionary the work is fan fiction of. Its values are kept as the user's corrections of the
 * work's source — the site has none of them — and suggested by the model from the work's fandom,
 * tags and texts, as any facet's.
 */
object UniverseFacet {
    const val KEY = "universe"

    val DEF = FacetDef(
        KEY, "Вселенная",
        filter = true,
        // One facet across the sources of the type: a universe is one whichever site a work is from
        shared = KEY,
        searchWeight = 0.9f,
        onCard = true,
        suggest = true,
    )

    /** The value of a universe of a catalogue: "wikidata:Q8337". */
    fun valueOf(catalogue: String, universe: String) = "$catalogue:$universe"

    /** The catalogue and the universe of a value; null when it is not one. */
    fun parse(value: String): Pair<String, String>? = value.indexOf(':').takeIf { it > 0 }?.let { value.substring(0, it) to value.substring(it + 1) }
}
