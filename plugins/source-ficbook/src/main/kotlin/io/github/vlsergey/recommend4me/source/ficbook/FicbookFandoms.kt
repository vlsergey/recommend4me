package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import org.jsoup.nodes.Document
import tools.jackson.databind.json.JsonMapper

/** What the site says of its fandoms: of a fandom's own page, the list of its works, and of its search of fandoms. */
internal class FicbookFandoms(private val items: ItemStore) {

    /**
     * A fandom's own page, [key] its address after "/fanfiction/": its heading names it as the works do — «Роулинг
     * Джоан «Гарри Поттер»» — and adds what it is of, in a line of its own: the original's name, its kind and years —
     * «/ Rowling Joanne «Harry Potter» / Серия книг, 1997 - 2007». That line is the fandom's description, blank when
     * the heading has none: the page read, the site saying nothing more. A page without the heading changes nothing.
     */
    fun page(doc: Document, key: String) {
        val heading = doc.selectFirst("h1.heading") ?: return
        val said = heading.selectFirst("span")?.text()?.trim()?.removePrefix("/")?.trim().orEmpty()
        items.describeFacetValues(FANDOM, mapOf(key to said))
    }

    /**
     * The answer of the site's search of fandoms ([Ficbook.fandomSearch]): every fandom found, of any group, by its
     * address — its group and its slug, "books/kuprin_aleksandr___olesja" — named as the works name it, «Куприн
     * Александр «Олеся»», and described by what it is of, as its own page's heading says it ("sec_title"). An answer
     * that is no such JSON changes nothing.
     */
    fun search(page: CapturedPage) {
        val found = runCatching { JSON.readTree(page.html) }.getOrNull()?.path("data")?.path("result") ?: return
        val names = HashMap<String, String>()
        val descriptions = HashMap<String, String>()
        found.forEach { f ->
            val group = f.path("group_crazy_title").asString("").trim()
            val slug = f.path("slug").asString("").trim()
            val title = f.path("title").asString("").trim()
            if (group.isEmpty() || slug.isEmpty() || title.isEmpty()) return@forEach
            val key = "$group/$slug"
            names[key] = title
            f.path("sec_title").asString("").trim().ifEmpty { null }?.let { descriptions[key] = it }
        }
        if (names.isNotEmpty()) items.nameFacetValues(FANDOM, names)
        if (descriptions.isNotEmpty()) items.describeFacetValues(FANDOM, descriptions)
    }

    private companion object {
        val JSON = JsonMapper.builder().build()
    }
}
