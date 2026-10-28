package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.ScrapeProgress
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.BASE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.FANDOM
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger(FanficsMeSync::class.java)

/**
 * The jobs of fanfics.me, by the site's search of every work ([LIST]: every direction, the latest
 * updated first) — twenty cards a page, each with all the work's header says but its text.
 *
 * UPDATES — from the first page down until the works updated before the last time, then what the
 * site's genres and warnings mean. SCRAPE_ALL — every page of the search from where the last run
 * stopped, the updates that came out meanwhile, then what the genres, the warnings and the events
 * of every fandom of the works mean. A work's own page — its text, its comments — is read only when
 * the user asks for it, or grades a work known by its card alone ([FanficsMe.refresh]).
 */
class FanficsMeSync(private val context: SourceContext, private val client: FanficsMeClient) {
    private val pages = FanficsMePages(context)

    fun run(mode: SourceMode, job: Job) {
        when (mode) {
            SourceMode.UPDATES -> updates(job)
            SourceMode.SCRAPE_ALL -> whole(job)
            // Not jobs: the pages the browser sends, a page asked for alone
            SourceMode.BROWSER, SourceMode.PAGE -> Unit
        }
    }

    private fun updates(job: Job) {
        readFromTop(job, UPDATE_PAGES, context.items.latestUpdate())
        help(job)
    }

    private fun whole(job: Job) {
        val knownUntil = context.items.latestUpdate()
        if (job.state(CATALOGUE_DONE) != "true") readCatalogue(job)
        // Updates that came out while the catalogue was read moved to its first pages
        readFromTop(job, UPDATE_PAGES, knownUntil)
        help(job)
        readKeywords(job)
        // The next whole scrape reads the catalogue from the start again
        job.state(NEXT_PAGE, null)
        job.state(CATALOGUE_DONE, null)
        job.state(NEXT_FANDOM, null)
        job.state(COMPLETE, "true")
    }

    private fun readCatalogue(job: Job) {
        var page = job.state(NEXT_PAGE)?.toIntOrNull() ?: 1
        if (page == 1) job.state(COMPLETE, null)
        var total = job.state(TOTAL_PAGES)?.toIntOrNull() ?: page
        job.phase("catalogue", total = total, processed = page - 1)
        while (page <= total) {
            job.checkCancelled()
            val read = readList(page)
            job.found(read.fresh)
            total = read.totalPages ?: total
            job.state(TOTAL_PAGES, total.toString())
            job.state(NEXT_PAGE, (page + 1).toString())
            job.progress(page, total)
            if (page % CHECKPOINT == 0) job.checkpoint()
            if (read.ids.isEmpty()) break
            page++
        }
        job.state(CATALOGUE_DONE, "true")
        log.info("the whole catalogue is read: {} pages, {} works", total, context.items.count())
    }

    /** From the first page down until a work updated before [knownUntil] — the list is ordered by the time of the update. */
    private fun readFromTop(job: Job, maxPages: Int, knownUntil: Instant?) {
        job.phase("feed", total = maxPages)
        for (page in 1..maxPages) {
            job.checkCancelled()
            val read = readList(page)
            job.found(read.fresh)
            job.progress(page)
            val reachedKnown = knownUntil != null && read.ids.any { id -> context.items.find(id)?.updatedAt?.isBefore(knownUntil) == true }
            if (reachedKnown || read.ids.isEmpty() || page >= (read.totalPages ?: page)) break
        }
        job.checkpoint()
    }

    private class Listing(val ids: List<String>, val fresh: Int, val totalPages: Int?)

    private fun readList(page: Int): Listing {
        val url = "$LIST&page=$page"
        val html = client.get(url) ?: return Listing(emptyList(), 0, null)
        val fresh = CARD_ID.findAll(html).map { it.groupValues[1] }.distinct().count { context.items.find(it) == null }
        val ids = pages.read(CapturedPage(url, html, Instant.now()))
        val total = PAGE_LINK.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        return Listing(ids, fresh, total?.coerceAtLeast(page))
    }

    /** The site's help on its genres and its warnings: what each means. */
    private fun help(job: Job) {
        job.phase("dictionary", total = HELP.size)
        HELP.forEachIndexed { i, url ->
            job.checkCancelled()
            client.get(url)?.let { pages.read(CapturedPage(url, it, Instant.now())) }
            job.progress(i + 1)
        }
    }

    /**
     * The events of every fandom the works are of, in its encyclopedia, the fandoms of the most works
     * first: what each event means. Resumed where the last run stopped.
     */
    private fun readKeywords(job: Job) {
        val works = HashMap<String, Int>()
        context.items.forEachFacet(FANDOM) { _, keys -> keys.forEach { works.merge(it, 1, Int::plus) } }
        val fandoms = works.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
        var next = job.state(NEXT_FANDOM)?.toIntOrNull() ?: 0
        job.state(TOTAL_FANDOMS, fandoms.size.toString())
        job.phase("dictionary", total = fandoms.size, processed = next)
        while (next < fandoms.size) {
            job.checkCancelled()
            val url = "$BASE/fandom${fandoms[next]}/keywords"
            try {
                client.get(url)?.let { pages.read(CapturedPage(url, it, Instant.now())) }
            } catch (e: Exception) {
                job.error("the events of fandom ${fandoms[next]} were not read: ${e.message}")
            }
            next++
            job.state(NEXT_FANDOM, next.toString())
            job.progress(next, fandoms.size)
        }
    }

    companion object {
        /** The search of every work of every direction, the latest updated first. */
        private const val LIST = "$BASE/find?reit1=0"

        /** The site's help on its genres and on its warnings. */
        private val HELP = listOf("$BASE/html?article=about_genres", "$BASE/html?article=about_warnings")

        /** A card of a work in a list, by its id. */
        private val CARD_ID = Regex("<div id=\"fic_(\\d+)\" class=\"FicTable")

        /** A page of the list in its paginator. */
        private val PAGE_LINK = Regex("[?&]page=(\\d+)#fics")

        /** Pages read from the top: of the updates, and after the whole catalogue for those that came out meanwhile. */
        private const val UPDATE_PAGES = 100

        /** Pages of the catalogue between the encodings and retrainings of the whole scrape. */
        private const val CHECKPOINT = 300

        private const val NEXT_PAGE = "nextPage"
        private const val TOTAL_PAGES = "totalPages"
        private const val CATALOGUE_DONE = "catalogueDone"
        private const val NEXT_FANDOM = "nextFandom"
        private const val TOTAL_FANDOMS = "totalFandoms"
        private const val COMPLETE = "complete"

        /**
         * How far the whole catalogue is: the search page by page, then the events of the fandoms;
         * complete once a whole scrape came to its end.
         */
        fun progress(context: SourceContext): ScrapeProgress {
            val items = context.items.count()
            val next = context.jobState(NEXT_PAGE)?.toIntOrNull()
            if (context.jobState(CATALOGUE_DONE) == "true") {
                val total = context.jobState(TOTAL_FANDOMS)?.toIntOrNull() ?: 0
                return ScrapeProgress("dictionary", context.jobState(NEXT_FANDOM)?.toIntOrNull() ?: 0, total, complete = false)
            }
            if (next != null) {
                val total = context.jobState(TOTAL_PAGES)?.toIntOrNull() ?: next
                return ScrapeProgress("catalogue", next - 1, total, complete = false)
            }
            if (context.jobState(COMPLETE) == "true") return ScrapeProgress("catalogue", items, items, complete = true)
            return ScrapeProgress("none", 0, 0, complete = false)
        }
    }
}
