package io.github.vlsergey.recommend4me.universe

/**
 * The facets the application gives every work of a content type with universes
 * ([ContentType.universes][io.github.vlsergey.recommend4me.contenttype.ContentType.universes]),
 * whichever site it is from: what a source names in its page decor or reads of the user's links.
 *
 * - [UNIVERSE]: the universes of the dictionary the work is fan fiction of — one or several;
 * - [CHARACTERS]: its main characters, of those universes;
 * - [PAIRINGS]: its pairings, each two of its characters.
 *
 * The site gives none of them: the user links a work, the model works the rest out from the line
 * the site writes the characters and pairings in ([SourceSchema.universeLine][io.github.vlsergey.recommend4me.source.SourceSchema.universeLine]),
 * the tags, the description and the chapters.
 */
object UniverseFacets {
    const val UNIVERSE = "universe"
    const val CHARACTERS = "characters"
    const val PAIRINGS = "pairings"
}
