package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.ReviewData
import org.jsoup.nodes.Document

/** The reviews of a work: as its page of reviews lists them, and a review read whole on its own page. */
internal class AuthorTodayReviews(private val items: ItemStore) {

    /**
     * The reviews of a work as its page of reviews lists them: excerpts of the long ones. A review read whole on its
     * own page before is not replaced by its excerpt.
     */
    fun list(doc: Document, workId: String): Boolean {
        if (items.find(workId) == null) return false
        val known = items.reviewContents(workId)
        val reviews =
            doc.select("article.post[id^=post_]").mapNotNull { article ->
                val reviewId = article.id().removePrefix("post_")
                val body = article.selectFirst(".rich-content") ?: return@mapNotNull null
                val text = PageText.of(body)
                val excerpt = article.selectFirst("a.btn[href=/review/$reviewId]") != null
                if (excerpt && (known[reviewId]?.length ?: 0) > text.length) return@mapNotNull null
                ReviewData(
                    reviewId,
                    article.selectFirst("time .mr a")?.text()?.trim(),
                    null,
                    text,
                    article.selectFirst("time [data-time]")?.attr("data-time")?.let(AuthorTodayPages::instant),
                )
            }
        if (reviews.isNotEmpty()) items.saveReviews(workId, reviews)
        return reviews.isNotEmpty()
    }

    /** A review read whole, on its own page, of a work known; the work's id. */
    fun review(doc: Document, reviewId: String): String? {
        val article = doc.selectFirst("article.full-post")
        val workId =
            article
                ?.selectFirst(".book-review-info .book-title a[href^=/work/]")
                ?.attr("href")
                ?.let { WORK_ID.find(it)?.groupValues?.get(1) }
                ?.takeIf { items.find(it) != null }
        val body = article?.select(".fr-view.rich-content")?.firstOrNull { it.closest(".book-review-info") == null }
        if (article == null || workId == null || body == null) return null
        items.saveReviews(
            workId,
            listOf(
                ReviewData(
                    reviewId,
                    article.selectFirst("header time .mr a")?.text()?.trim(),
                    null,
                    PageText.of(body),
                    article.selectFirst("header [data-time]")?.attr("data-time")?.let(AuthorTodayPages::instant),
                )
            ),
        )
        return workId
    }

    private companion object {
        val WORK_ID = Regex("^/work/(\\d+)")
    }
}
