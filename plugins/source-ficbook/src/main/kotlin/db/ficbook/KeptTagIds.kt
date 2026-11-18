package db.ficbook

import java.sql.Connection
import java.util.zip.GZIPInputStream
import org.jsoup.Jsoup
import tools.jackson.databind.json.JsonMapper

/**
 * WHAT THE KEPT PAGES OF FICBOOK SAY THE IDS OF ITS TAGS' NAMES ARE, by the name in lower case, for the migrations of
 * its tags (V4, V5): of the links of the tags of the works and the lists, of the tags' own pages, of the tags the
 * site's search of tags answered, and of their synonyms — a synonym's name is its tag's: its own id in the answer
 * counts the synonyms, not the tags (the synonym "Titfuck" 1023, the tag "Лихтенштейн" 1023; 2026-11-18). And the name
 * of every tag by its id, as the search and the tag's page name it, else as its links write it.
 */
internal class KeptTagIds {
    private val linked = HashMap<String, MutableSet<Long>>()
    private val found = HashMap<String, MutableSet<Long>>()
    private val synonyms = HashMap<String, MutableSet<Long>>()
    private val titles = HashMap<Long, String>()
    private val written = HashMap<Long, MutableMap<String, Int>>()

    /**
     * The id of the tag [name]: the one the links give, else the one a search found, else the tag it is a synonym of.
     */
    fun of(name: String): Long? {
        val key = name.trim().lowercase()
        return listOf(linked, found, synonyms).firstNotNullOfOrNull { it[key]?.singleOrNull() }
    }

    /** The name of the tag [id], as the search or its page names it, else as its links write it most. */
    fun title(id: Long): String? = titles[id] ?: written[id]?.maxByOrNull { it.value }?.key

    /** Every kept page of the source's file read. */
    fun read(c: Connection) =
        c.prepareStatement("SELECT url, html FROM captured_page").use { s ->
            s.executeQuery().use { r ->
                while (r.next()) {
                    page(
                        r.getString(1),
                        GZIPInputStream(r.getBinaryStream(2)).use { String(it.readBytes(), Charsets.UTF_8) },
                    )
                }
            }
        }

    /** One kept page: an answer of the search of tags, a tag's own page, a page of works. */
    fun page(url: String, html: String) {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').let { "/$it" }
        if (path == "/tags/search") {
            val tags = runCatching { JSON.readTree(html) }.getOrNull()?.path("data")?.path("tags") ?: return
            tags.forEach { t ->
                val id = t.path("id").asLong(0L)
                val title = t.path("title").asString("").trim()
                add(found, title, id)
                if (id > 0 && title.isNotEmpty()) titles[id] = title
                t.path("synonyms").forEach { add(synonyms, it.path("title").asString(""), id) }
            }
            return
        }
        val doc = Jsoup.parse(html)
        TAG_PAGE.matchEntire(path)?.groupValues?.get(1)?.toLong()?.let { id ->
            doc.selectFirst("h1.heading")
                ?.text()
                ?.let { TAG_HEADING.find(it)?.groupValues?.get(1)?.trim() }
                ?.let {
                    add(found, it, id)
                    titles.putIfAbsent(id, it)
                }
        }
        doc.select("a.tag[href]").forEach { a ->
            TAG_PAGE.matchEntire(a.attr("href"))?.groupValues?.get(1)?.toLong()?.let { id ->
                val text = a.text().trim()
                add(linked, text, id)
                if (text.isNotEmpty()) written.getOrPut(id) { HashMap() }.merge(text, 1, Int::plus)
            }
        }
    }

    private fun add(to: HashMap<String, MutableSet<Long>>, name: String, id: Long) {
        val key = name.trim().lowercase()
        if (key.isNotEmpty() && id > 0) to.getOrPut(key) { HashSet() } += id
    }

    private companion object {
        val JSON = JsonMapper.builder().build()

        /** A tag's own page, "/tags/1682". */
        val TAG_PAGE = Regex("/tags/(\\d+)/?")

        /** The heading of a tag's page: the name between the outer quotes. */
        val TAG_HEADING = Regex("^Метка «(.*)»\\s*-\\s*фанфики")
    }
}
