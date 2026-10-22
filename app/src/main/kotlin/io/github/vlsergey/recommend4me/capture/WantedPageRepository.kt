package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.database.source.tables.references.WANTED_PAGE
import io.github.vlsergey.recommend4me.source.WantedPage
import org.jooq.DSLContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

/**
 * The requests of the site one source wants the browser to make for it ([WantedPage]): each asked
 * once, and done once the browser sent back what the site answered — a request the site answered
 * with nothing to read is not asked again.
 */
class WantedPageRepository(private val db: DSLContext) {

    /** Asks for the requests not asked before; returns those not answered yet, asked now or before. */
    fun want(pages: List<WantedPage>, now: Instant): List<WantedPage> {
        if (pages.isEmpty()) return emptyList()
        val known = db.select(WANTED_PAGE.URL, WANTED_PAGE.FETCHED_AT).from(WANTED_PAGE).where(WANTED_PAGE.URL.`in`(pages.map { it.url }))
            .fetch().associate { it.value1()!! to it.value2() }
        pages.filter { it.url !in known }.forEach { p ->
            db.insertInto(WANTED_PAGE)
                .set(WANTED_PAGE.URL, p.url).set(WANTED_PAGE.FORM, p.form?.let(::encoded)).set(WANTED_PAGE.WANTED_AT, now)
                .onDuplicateKeyIgnore().execute()
        }
        return pages.filter { known[it.url] == null }
    }

    /** The requests not answered yet, the oldest first. */
    fun awaited(limit: Int): List<WantedPage> =
        db.select(WANTED_PAGE.URL, WANTED_PAGE.FORM).from(WANTED_PAGE).where(WANTED_PAGE.FETCHED_AT.isNull)
            .orderBy(WANTED_PAGE.WANTED_AT).limit(limit)
            .fetch { WantedPage(it.value1()!!, it.value2()?.let(::decoded)) }

    /** The request of [url] answered; nothing when it was not asked. */
    fun fetched(url: String, now: Instant) {
        db.update(WANTED_PAGE).set(WANTED_PAGE.FETCHED_AT, now).where(WANTED_PAGE.URL.eq(url)).execute()
    }

    companion object {
        /** A form kept as a form's body is sent: "name=value&…", URL-encoded. */
        private fun encoded(form: Map<String, String>): String =
            form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }

        private fun decoded(form: String): Map<String, String> =
            form.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val (k, v) = (pair.split('=', limit = 2) + "").take(2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
    }
}
