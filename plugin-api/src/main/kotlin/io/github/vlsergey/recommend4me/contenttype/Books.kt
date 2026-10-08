package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * BOOKS — novels, fan fiction, serials of any site — and their standard facets: what every source
 * of books maps its own facets onto. What the work is about is the text of the role
 * [DESCRIPTION][io.github.vlsergey.recommend4me.source.TextRole.DESCRIPTION] of every source.
 */
object Books {
    const val ID = "books"

    val AUTHOR = StandardFacet("author", "Автор", role = FacetRole.AUTHOR, onCard = true, names = true, searchWeight = 1.0f)

    /** Whether the whole of the work is written: of a site that tells only that, finished or in progress. */
    object Status {
        val FINISHED = StandardValue("finished", "Завершено")
        val IN_PROGRESS = StandardValue("in-progress", "В процессе")
        val FROZEN = StandardValue("frozen", "Заморожено")
    }

    val STATUS = StandardFacet(
        "status", "Статус",
        values = listOf(Status.FINISHED, Status.IN_PROGRESS, Status.FROZEN),
        role = FacetRole.STATUS, filter = true, onCard = true,
    )

    val TAG = StandardFacet("tag", "Тег", role = FacetRole.CONTENT, searchWeight = 0.9f, suggest = true)

    /** The value of a signal that is either set or not. */
    val YES = StandardValue("yes", "да")

    /**
     * The user marked the work read on the site: on its shelf of the finished, with its box "read"
     * ticked. Shown, not read by the model: it is known only of the works the user has read.
     */
    val READ = StandardSignal("read", "Прочитано", listOf(YES), feature = false)

    /** The user liked the work on the site. Shown, not read by the model: it is the user's verdict, not something of the work. */
    val LIKED = StandardSignal("liked", "Понравилось", listOf(YES), feature = false)

    val TYPE = ContentType(
        id = ID,
        title = "Книги",
        grades = listOf("Не нравится", "Можно почитать", "В целом понравилась", "Очень хорошая", "Хочу ещё"),
        verb = "читать",
        universes = true,
        facets = listOf(AUTHOR, STATUS, UniverseFacets.KIND_FACET, TAG),
        signals = listOf(READ, LIKED),
    )
}
