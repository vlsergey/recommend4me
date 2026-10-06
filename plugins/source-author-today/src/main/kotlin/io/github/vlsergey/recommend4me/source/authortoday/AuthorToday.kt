package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef

/**
 * author.today — books, read in the browser and seen by the extension only: the application
 * requests no page of the site. What a page shows is kept: the cards of every list, a work's page
 * with its table of contents, its reviews, the text of every chapter read; and the user's own marks
 * on the work — the shelf of the library, the like, the reactions, "not interested".
 */
class AuthorToday : Source {
    override val id = ID
    override val title = "Author.Today"
    override val contentType = "books"
    override val homepage = BASE
    override val modes = setOf(SourceMode.BROWSER)

    override val schema = SourceSchema(
        facets = listOf(
            FacetDef(AUTHOR, "Автор", feature = true, shared = "author", searchWeight = 1.0f, onCard = true, names = true),
            FacetDef(GENRE, "Жанр", filter = true, onCard = true, searchWeight = 0.8f),
            FacetDef(FORM, "Форма", filter = true),
            FacetDef(TAG, "Тег", shared = "tag", searchWeight = 0.9f),
            FacetDef(STATUS, "Статус", filter = true, onCard = true),
            FacetDef(SERIES, "Цикл", searchWeight = 0.7f),
        ),
        numbers = listOf(
            NumberDef(CHARS, "Знаков", NumberScale.LOG, sortable = true),
            NumberDef(VIEWS, "Просмотры", NumberScale.LOG, sortable = true),
            NumberDef(LIKES, "Понравилось", NumberScale.LOG, sortable = true),
            NumberDef(COMMENTS, "Комментарии", NumberScale.LOG),
            NumberDef(REVIEWS, "Рецензии", NumberScale.LOG),
            NumberDef(AWARDS, "Награды", NumberScale.LOG),
        ),
        texts = listOf(
            TextDef(ANNOTATION, "Аннотация", block = "text:annotation", searchWeight = 0.6f),
            TextDef(NOTES, "Примечания автора", block = "text:notes", searchWeight = 0.5f),
        ),
        reviewsLabel = "Рецензии",
        partsLabel = "Главы",
    )

    override val capturePatterns = listOf(
        Regex("https://author\\.today/(work|reader|review|search|collection|collections|top|u/[^/]+/(works|library|reviews))([/?#].*)?"),
    )

    override val parserVersion = 1

    override fun itemUrl(itemId: String) = "$BASE/work/$itemId"

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = AuthorTodayPages(context).read(page)

    companion object {
        const val ID = "author.today"
        const val BASE = "https://author.today"

        const val AUTHOR = "author"
        const val GENRE = "genre"
        const val FORM = "form"
        const val TAG = "tag"
        const val STATUS = "status"
        const val SERIES = "series"

        const val CHARS = "chars"
        const val VIEWS = "views"
        const val LIKES = "likes"
        const val COMMENTS = "comments"
        const val REVIEWS = "reviews"
        const val AWARDS = "awards"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"

        /** The user's own: the shelf of the library the work is on. */
        const val SIGNAL_LIBRARY = "library"
        const val SIGNAL_LIKED = "liked"
        const val SIGNAL_REACTION = "reaction"
        const val SIGNAL_HIDDEN = "hidden"
    }
}
