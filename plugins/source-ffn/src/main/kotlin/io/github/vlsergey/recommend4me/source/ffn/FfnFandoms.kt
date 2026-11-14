package io.github.vlsergey.recommend4me.source.ffn

import java.net.URI
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * THE FANDOMS OF THE SITE AND THEIR SECTIONS. A fandom is known by its archive's path, "anime/Naruto" — its section and
 * its name on the site, the page "/anime/Naruto/" ([Ffn.valueUrl]) — but a card of a search or of an author names it
 * alone, its section told by no part of it. So every path a page links is kept here by the fandom's name, in the
 * plugin's own table: a section's list of its fandoms, a fandom's archive itself, the links over a work's header; a
 * fandom a page names alone is the one of its name kept, when exactly one is.
 */
internal class FfnFandoms(private val db: DSLContext) {

    /** The fandoms [paths] tells — name to its archive's path — kept where new; the names of those new. */
    fun learn(paths: Map<String, String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val kept =
            db.select(NAME, PATH).from(TABLE).where(NAME.`in`(paths.keys)).fetch().map { it.value1() to it.value2() }
        val fresh = paths.filter { (name, path) -> (name to path) !in kept }
        fresh.forEach { (name, path) -> db.insertInto(TABLE, NAME, PATH).values(name, path).execute() }
        return fresh.keys
    }

    /**
     * The keys of the fandoms [names] — the path of each a page linked ([known]), else the one path its name is kept
     * with, else of the paths of its name the one the work has already ([had]: a page linked it before — "Supergirl" of
     * tv and of comics, the work's archive told which); the name itself of one never linked, or of two sections and
     * none chosen.
     */
    fun keysOf(
        names: List<String>,
        known: Map<String, String> = emptyMap(),
        had: Collection<String> = emptyList(),
    ): List<String> {
        val unknown = names.filter { it !in known }
        val kept =
            if (unknown.isEmpty()) emptyMap()
            else
                db.select(NAME, PATH)
                    .from(TABLE)
                    .where(NAME.`in`(unknown))
                    .fetch()
                    .groupBy({ it.value1()!! }, { it.value2()!! })
        return names.map { name ->
            known[name] ?: kept[name]?.let { paths -> paths.singleOrNull() ?: paths.singleOrNull { it in had } } ?: name
        }
    }

    /**
     * The two fandoms of a crossover's line, "A & B": split at its one " & ", else at the one place both sides are
     * fandoms kept — "Young Justice & Miraculous: Tales of Ladybug & Cat Noir"; null where the names kept choose no one
     * place: no fandom guessed.
     */
    fun crossover(text: String): List<String>? {
        val splits =
            Regex(Regex.escape(AND)).findAll(text).map {
                listOf(text.take(it.range.first), text.drop(it.range.last + 1))
            }
        val all = splits.toList()
        if (all.size <= 1) return all.singleOrNull()
        val known = db.select(NAME).from(TABLE).where(NAME.`in`(all.flatten().toSet())).fetchSet { it.value1() }
        return all.singleOrNull { split -> split.all { it in known } }
    }

    companion object {
        /** What a crossover's line writes between its two fandoms. */
        private const val AND = " & "

        private val TABLE = DSL.table("FFN_FANDOM")
        private val NAME = DSL.field("NAME", String::class.java)
        private val PATH = DSL.field("PATH", String::class.java)

        /** The sections of the site a fandom is in. */
        private const val SECTIONS = "anime|book|cartoon|comic|game|misc|movie|play|tv"

        /** A section's list of its fandoms: "/anime/", "/anime/?l=c". */
        private val SECTION_PAGE = Regex("^https?://(?:www\\.)?fanfiction\\.net/(?:$SECTIONS)/?(?:[?#].*)?$")

        /** A fandom's archive: "/anime/Naruto/", whatever its address adds after it. */
        private val ARCHIVE = Regex("^/((?:$SECTIONS)/[^/?#]+)/")

        /** A fandom's archive's path as an address names it, its letters undone of their percent-encoding. */
        private fun pathOf(address: String): String? = runCatching {
            URI(address).path
        }
            .getOrNull()
            ?.let { ARCHIVE.find(it)?.groupValues?.get(1) }

        /**
         * Of a section's list of its fandoms, every fandom it links: its name — the link's title, never cut — to its
         * path.
         */
        fun ofSection(doc: Document, url: String): Map<String, String> =
            if (!SECTION_PAGE.matches(url)) emptyMap()
            else
                doc.select("#list_output a[href][title]")
                    .mapNotNull { a ->
                        val name = a.attr("title").trim().ifEmpty { null }
                        pathOf(a.attr("href"))?.let { path -> name?.let { it to path } }
                    }
                    .toMap()

        /** The path of a fandom's archive [url], of the fandom [name] the page heads with. */
        fun ofArchive(url: String, name: String?): Map<String, String> =
            name?.let { n -> pathOf(url)?.let { mapOf(n to it) } }.orEmpty()

        /** The fandom a link over a work's header names, to its path; none of a link that is not a fandom's archive. */
        fun ofLink(a: Element): Map<String, String> =
            pathOf(a.attr("href"))?.let { mapOf(a.text().trim() to it) }.orEmpty()
    }
}
