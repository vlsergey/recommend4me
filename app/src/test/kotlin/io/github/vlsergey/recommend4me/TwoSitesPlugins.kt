package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.JobCancelled
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SettingDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import java.time.Instant
import java.util.Collections
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The plugins of the two sites of the tests ([TwoSitesTestBase]): a site read in the browser, [Alpha], and one the
 * application scrapes itself, [Beta]; texts encoded by their words.
 */
@TestConfiguration
class TwoSitesPlugins {
    @Bean fun alpha(): Source = Alpha()

    @Bean fun beta(): Source = Beta()

    @Bean fun wordsEncoder(): TextEncoder = SitePlugins.WordsEncoder()

    /**
     * A site read in the browser: pages "<id>|<title>|<tags>|<annotation>|<picture>|<review>". Its parser's version can
     * be raised: from version 2 it reads the title in capitals.
     */
    class Alpha : Source {
        override val id = "alpha"
        override val contentType = "books"
        override val homepage = "https://alpha.example"
        override val schema =
            SourceSchema(
                facets = listOf(Books.TAG.asFacet(), Books.AUTHOR.asFacet()),
                numbers = listOf(StandardNumbers.WORDS.asNumber()),
                texts = listOf(TextDef("annotation", block = "text:annotation")),
                reviews = true,
            )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://alpha\\.example/work/.*"))

        override fun itemUrl(itemId: String) = "https://alpha.example/work/$itemId"

        override val parserVersion: Int
            get() = version

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val parts = page.html.split('|')
            val (id, title, tags) = parts
            val annotation = parts[3]
            val picture = parts[4]
            val review = parts[5]
            context.items.upsert(
                ItemHead(
                    id,
                    itemUrl(id),
                    if (version >= 2) title.uppercase() else title,
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                page.capturedAt,
            )
            context.items.setFacet(
                id,
                Books.TAG.key,
                tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it) },
            )
            context.items.setTexts(id, mapOf("annotation" to annotation))
            if (picture.isNotBlank()) context.items.setPictures(id, listOf("${TwoSitesTestBase.pictureBase}/$picture"))
            if (review.isNotBlank())
                context.items.saveReviews(id, listOf(ReviewData("r1", "reader", null, review, null)))
            return listOf(id)
        }

        companion object {
            @Volatile var version = 1
        }
    }

    /**
     * A site the application scrapes: a job of the whole catalogue writes three works and waits till it is stopped; a
     * job of the updates writes one and ends. The page of a work it is asked to refresh is "loaded" and remembered.
     */
    class Beta : Source {
        override val id = "beta"
        override val contentType = "books"
        override val homepage = "https://beta.example"
        override val schema =
            SourceSchema(
                facets = listOf(Books.TAG.asFacet(), Books.AUTHOR.asFacet()),
                numbers = listOf(StandardNumbers.CHARS.asNumber()),
                texts = listOf(TextDef("annotation", block = "text:annotation")),
            )
        override val modes = setOf(SourceMode.SCRAPE_ALL, SourceMode.UPDATES, SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://beta\\.example/work/.*"))

        override fun itemUrl(itemId: String) = "https://beta.example/work/$itemId"

        override fun valueUrl(facet: String, key: String, name: String) =
            if (facet == Books.AUTHOR.key) "https://beta.example/author/$key" else null

        override val settings = listOf(SettingDef("cookie", secret = true), SettingDef("delay", default = "5"))

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val parts = page.html.split('|')
            val (id, title, tags) = parts
            write(context, id, title, tags, parts[3], page.capturedAt)
            return listOf(id)
        }

        private fun write(
            context: SourceContext,
            id: String,
            title: String,
            tags: String,
            annotation: String,
            now: Instant,
        ) {
            context.items.upsert(
                ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")),
                now,
            )
            context.items.setFacet(
                id,
                Books.TAG.key,
                tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it) },
            )
            context.items.setTexts(id, mapOf("annotation" to annotation))
        }

        override fun scrape(mode: SourceMode, context: SourceContext, job: Job) {
            if (mode == SourceMode.UPDATES) {
                write(context, "u1", "An update", "ocean", "News from the sea", Instant.now())
                job.found(1)
                return
            }
            job.phase("catalogue", total = 3)
            (1..3).forEach { k ->
                write(context, "s$k", "Scraped $k", "ocean", "A scraped tale of the sea $k", Instant.now())
                job.progress(k, 3)
            }
            job.found(3)
            // The rest of a long catalogue: till the user stops it
            while (!job.cancelled) Thread.sleep(10)
            throw JobCancelled()
        }

        override fun needsRefresh(itemId: String, context: SourceContext) = itemId.startsWith("b")

        override fun refresh(itemId: String, context: SourceContext): Boolean {
            refreshed += itemId
            context.items.setTexts(itemId, mapOf("annotation" to "The whole page of $itemId, loaded once graded"))
            return true
        }

        companion object {
            val refreshed: MutableList<String> = Collections.synchronizedList(ArrayList())
        }
    }
}
