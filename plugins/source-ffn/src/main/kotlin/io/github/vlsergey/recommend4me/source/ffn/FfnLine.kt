package io.github.vlsergey.recommend4me.source.ffn

/**
 * The line the site makes of a work by its own template — no free text, the site's fields in its order, " - " between
 * them: "[Crossover - A & B - | A - ]Rated: T - English - Romance/Drama - [Harry P., Hermione G.] Ron W. - Chapters:
 * 3 - Words: 24,006 - Reviews: 7 - Favs: 39 - Follows: 61 - Updated: … - Published: … - Status: Complete - id: 1". The
 * language right after the rating; the genres, when the work has them, after it, every one of the site's own; the
 * characters the one field of no caption left — after the genres on a work's page, after the days on a card.
 */
internal class FfnLine(
    /** The fandoms the line names: a crossover's two, or the one a card of an author's page leads with. */
    val fandoms: List<String>,
    /** The rating as the site writes it: "K", "K+", "T", "M". */
    val rating: String?,
    val language: String?,
    val genres: List<String>,
    /** The characters' field as written, "[Harry P., Hermione G.] Ron W.": its pairings in brackets. */
    val characters: String?,
    /** The numbers by their captions: "Chapters", "Words", "Reviews", "Favs", "Follows". */
    val counts: Map<String, Long>,
    val complete: Boolean,
) {
    companion object {
        /** The genres of the site, its own list: the only words of the field of genres. */
        val GENRES =
            setOf(
                "Adventure",
                "Angst",
                "Crime",
                "Drama",
                "Family",
                "Fantasy",
                "Friendship",
                "General",
                "Horror",
                "Humor",
                "Hurt/Comfort",
                "Mystery",
                "Parody",
                "Poetry",
                "Romance",
                "Sci-Fi",
                "Spiritual",
                "Supernatural",
                "Suspense",
                "Tragedy",
                "Western",
            )

        private const val CROSSOVER = "Crossover"
        private const val COMPLETE = "Complete"
        private const val RATED = "Rated:"
        private val CAPTIONED = Regex("^(Chapters|Words|Reviews|Favs|Follows|Updated|Published|Status|id):\\s*(.*)$")

        /** The line of the text [line] — the element's text, its days as they are shown. */
        fun of(line: String): FfnLine {
            val fields = line.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            val rated = fields.indexOfFirst { it.startsWith(RATED) }
            val before = if (rated < 0) emptyList() else fields.subList(0, rated)
            val after = if (rated < 0) fields else fields.subList(rated + 1, fields.size)
            val captioned =
                after.mapNotNull { CAPTIONED.matchEntire(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
            val free = after.drop(1).filter { CAPTIONED.matchEntire(it) == null && it != COMPLETE }
            val genres = free.firstOrNull()?.let(::genresOf)
            return FfnLine(
                fandoms = fandomsOf(before),
                rating = fields.getOrNull(rated)?.removePrefix(RATED)?.trim()?.removePrefix("Fiction")?.trim(),
                language = if (rated < 0) null else after.firstOrNull(),
                genres = genres.orEmpty(),
                characters = free.drop(if (genres == null) 0 else 1).firstOrNull(),
                counts =
                    listOf("Chapters", "Words", "Reviews", "Favs", "Follows")
                        .mapNotNull { k -> captioned[k]?.replace(",", "")?.trim()?.toLongOrNull()?.let { k to it } }
                        .toMap(),
                complete = COMPLETE in after || captioned["Status"] == COMPLETE,
            )
        }

        /** "Crossover - A & B": the two; "A": the one. */
        private fun fandomsOf(before: List<String>): List<String> =
            if (before.firstOrNull() == CROSSOVER) before.drop(1).flatMap { it.split(" & ") } else before

        /** The genres of the field, "Romance/Hurt/Comfort"; null of a field that is not of genres alone. */
        private fun genresOf(field: String): List<String>? {
            val words =
                field.replace("Hurt/Comfort", HURT_COMFORT).split('/').map { it.replace(HURT_COMFORT, "Hurt/Comfort") }
            return words.takeIf { w -> w.isNotEmpty() && w.all { it in GENRES } }
        }

        /** The one genre with "/" in its name, kept whole while the field is split by it. */
        private const val HURT_COMFORT = "\u0000"

        /**
         * The characters' field split by its template: every pairing in brackets, its names by ", " — "[Harry P.,
         * Hermione G.]" one entry "Harry P./Hermione G." — and every name outside them an entry of its own.
         */
        fun castOf(field: String): List<String> {
            val pairings = Regex("\\[([^\\]]*)]").findAll(field).map { m -> names(m.groupValues[1]).joinToString("/") }
            val alone = names(field.replace(Regex("\\[[^\\]]*]"), ","))
            return (pairings.toList() + alone).filter { it.isNotEmpty() }
        }

        private fun names(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
