package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.contenttype.StandardValue

/**
 * The facets the application gives every work of a content type with universes
 * ([ContentType.universes][io.github.vlsergey.recommend4me.contenttype.ContentType.universes]),
 * whichever site it is from: what a source names in its page decor or reads of the user's links.
 *
 * - [KIND]: whether the work is fan fiction ([FANFICTION]) or an original work ([ORIGINAL]) — the
 *   one a source may say itself: a site's fandom "originals", its genre "fan fiction";
 * - [UNIVERSE]: the universes of the dictionary the work is fan fiction of — one or several;
 * - [CHARACTERS]: its main characters, of those universes;
 * - [PAIRINGS]: its pairings, each two of its characters ("pair:<character>|<character>", the two
 *   in the order of their keys).
 *
 * Of the last three the site gives none: the user links a work, the model works the rest out from
 * the line the site writes the characters and pairings in ([SourceSchema.universeLine][io.github.vlsergey.recommend4me.source.SourceSchema.universeLine]),
 * the tags, the description and the chapters.
 */
object UniverseFacets {
    const val KIND = "kind"
    const val FANFICTION = "fanfiction"
    const val ORIGINAL = "original"

    /** [KIND] as the standard facet of every type with universes: fan fiction or an original work, nothing else. */
    val KIND_FACET = StandardFacet(
        KIND, "Фанфик или оригинал",
        values = listOf(StandardValue(FANFICTION, "Фанфик"), StandardValue(ORIGINAL, "Оригинальное произведение")),
        filter = true, onCard = true, suggest = true,
    )

    const val UNIVERSE = "universe"
    const val CHARACTERS = "characters"
    const val PAIRINGS = "pairings"
}
