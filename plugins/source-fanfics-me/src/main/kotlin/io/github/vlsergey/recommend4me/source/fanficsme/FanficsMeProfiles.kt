package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.SourceContext
import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A MEMBER'S OWN PAGE, "/user502152": the places on the web it lists as theirs — the lines under "Места в сети" of its
 * detailed information, each a site's name and the address of the member's page there, led through the site's
 * "/go.php?url=" — the addresses themselves ([SourceContext.authorPlaces]). Of the rest of the information, its
 * contacts (an e-mail) included, nothing is read.
 */
internal class FanficsMeProfiles(private val context: SourceContext) {

    /** The places the page [doc] of the member [path] names lists, of a member's own page. */
    fun read(doc: Document, path: String) {
        val member = MEMBER_PAGE.matchEntire(path)?.groupValues?.get(1) ?: return
        val info = doc.selectFirst("div#ProfileRight") ?: return
        context.authorPlaces(member, places(info))
    }

    /** The addresses of the lines of the section "Места в сети", every table of the information read in order. */
    private fun places(info: Element): List<String> {
        val out = ArrayList<String>()
        var inPlaces = false
        info.select("table.ProfileInfo tr").forEach { row ->
            val heading = row.selectFirst("h2")
            if (heading != null) inPlaces = clean(heading.text()) == PLACES
            else if (inPlaces) row.select("a[href]").mapNotNullTo(out) { addressOf(it) }
        }
        return out.distinct()
    }

    /** The address a link of the page leads to: through "/go.php?url=", the one it names. */
    private fun addressOf(a: Element): String? {
        val href = a.absUrl("href")
        val uri = runCatching { URI(href) }.getOrNull()
        val led =
            if (uri?.path == GO) {
                uri.rawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("url=") }
                    ?.removePrefix("url=")
                    ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            } else href
        return led?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private companion object {
        /** A member's own page: their id. */
        val MEMBER_PAGE = Regex("/user(\\d+)/?")

        /** The heading of the places on the web among the member's information. */
        const val PLACES = "Места в сети"

        /** The site's page leading out to another site. */
        const val GO = "/go.php"
    }
}
