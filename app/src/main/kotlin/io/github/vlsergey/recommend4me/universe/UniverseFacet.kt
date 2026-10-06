package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * The facets the application gives every work of a content type with universes ([UniverseFacets]).
 * Their values are kept as the user's corrections of the work's source — the site has none — and
 * worked out by the model, as any facet's:
 *
 * - the kind — fan fiction or an original work — is the site's when it says, suggested otherwise;
 * - the universes are suggested, to be confirmed, to a work not known to be original: the work's
 *   characters are chosen within them;
 * - the main characters are worked out of the characters of the work's universes and the original
 *   characters, the pairings of pairs of the work's characters — given to the work at once.
 */
object UniverseFacet {
    const val KEY = UniverseFacets.UNIVERSE

    val DEF = FacetDef(
        KEY, "Вселенная",
        filter = true,
        // One facet across the sources of the type: a universe is one whichever site a work is from
        shared = KEY,
        searchWeight = 0.9f,
        onCard = true,
        suggest = true,
    )

    /** The values of the kind of a work, with their names. */
    val KINDS = mapOf(UniverseFacets.FANFICTION to "Фанфик", UniverseFacets.ORIGINAL to "Оригинальное произведение")

    /** The facets of a source of a type with universes: its line of characters and pairings shown above the worked out ones. */
    fun defs(schema: SourceSchema): List<FacetDef> = listOf(
        FacetDef(UniverseFacets.KIND, "Фанфик или оригинал", filter = true, shared = UniverseFacets.KIND, onCard = true, suggest = true),
        DEF,
        FacetDef(
            UniverseFacets.CHARACTERS, "Главные персонажи",
            shared = UniverseFacets.CHARACTERS, searchWeight = 0.8f, infer = true, original = schema.universeLine,
        ),
        FacetDef(
            UniverseFacets.PAIRINGS, "Пэйринги",
            shared = UniverseFacets.PAIRINGS, searchWeight = 0.7f, infer = true, original = schema.universeLine,
        ),
    )

    /** The value of a universe or a character of a catalogue: "wikidata:Q8337". */
    fun valueOf(catalogue: String, id: String) = "$catalogue:$id"

    /** The catalogue and the id of a value; null when it is not one. */
    fun parse(value: String): Pair<String, String>? = value.indexOf(':').takeIf { it > 0 }?.let { value.substring(0, it) to value.substring(it + 1) }

    /** The original characters of a work, of no universe: the values every work may have besides its universes' characters. */
    val ORIGINAL_CHARACTERS = mapOf(
        "oc:female" to "ОЖП (оригинальный женский персонаж)",
        "oc:male" to "ОМП (оригинальный мужской персонаж)",
    )

    /** A pairing of two characters: one whatever their order. */
    fun pairing(a: String, b: String): String = listOf(a, b).sorted().let { (x, y) -> "pair:$x|$y" }

    /** The two characters of a pairing; null when the value is not one. */
    fun members(pairing: String): Pair<String, String>? =
        pairing.removePrefix("pair:").takeIf { it != pairing }?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
}
