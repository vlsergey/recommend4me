package io.github.vlsergey.recommend4me.contenttype

/**
 * A kind of works ranked together: graded on one scale and put in order by one model, whichever
 * source they came from. A plugin may declare one as a bean; the application declares `games`
 * and `books`.
 *
 * [title] and the labels of [grades] are shown to the user as they are.
 */
class ContentType(
    val id: String,
    val title: String,
    /** The labels of the grades 1..5, lowest first: what "1" means for a game is not what it means for a book. */
    val grades: List<String>,
    /** What the user does with a work: "play", "read" — for the hints of the interface. */
    val verb: String,
    /**
     * Works of the type may be written in the universes of others — fan fiction: the type has a
     * dictionary of universes and their characters, and every work of it the facet "universe".
     */
    val universes: Boolean = false,
    /** The facets every source of the type maps its own onto ([StandardFacet]). */
    val facets: List<StandardFacet> = emptyList(),
    /** The user's actions on a site every source of the type tells alike ([StandardSignal]). */
    val signals: List<StandardSignal> = emptyList(),
) {
    init {
        require(grades.size == 5) { "A content type labels five grades, $id labels ${grades.size}" }
    }
}
