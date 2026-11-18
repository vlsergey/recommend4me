package db.ficbook

import java.sql.Connection
import java.util.zip.GZIPInputStream
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

private val log = LoggerFactory.getLogger(V4__tags_by_their_ids::class.java)

/**
 * A TAG OF FICBOOK IS THE SITE'S ID OF ITS PAGE, ITS NAME AND DESCRIPTION BOUND TO IT (user, 2026-11-18: "a tag is a
 * numeric id to the site; if you do not know the id, you cannot link to the tag's page"). The tags were keyed by their
 * names in lower case — of the site's pages and of the dump alike — and are given their ids, of every work at once: a
 * name is one tag of the site, its id what the kept pages say of it — the links of the tags of the works and the lists
 * first, then the tags the site's search of tags answered, then their synonyms. A name of no id found is keyed by the
 * name alone ("name:"), asked of the site's search later. The other names the site files under a tag are taken away:
 * the kept answers of the search, read again by the parser's version 17, give them by their ids.
 *
 * Run outside a transaction, one tag at a time: of the 6.9 million tags of the works rewritten, every step kept as it
 * is done; a key already an id or a name is left — no name of a tag of the works is all digits (2026-11-18) — so the
 * migration broken off is done again from where it stopped.
 */
@Suppress("ClassName")
class V4__tags_by_their_ids : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val ids = KeptTagIds().also { it.read(c) }
        val moves = moves(c, ids)
        moves.forEach { (old, new) -> move(c, old, new.first, new.second) }
        // The synonyms by their names: given again by their ids as the kept answers are read again
        c.prepareStatement("DELETE FROM facet_value_synonym WHERE facet = 'tag'").use { it.executeUpdate() }
        // What is left of the names of no work — the synonyms' — goes with them
        listOf("facet_value", "facet_value_description", "facet_value_vector").forEach { table ->
            c.prepareStatement(
                    "DELETE FROM $table WHERE facet = 'tag' AND value_key NOT LIKE 'name:%' " +
                        "AND NOT REGEXP_LIKE(value_key, '^[0-9]+$')"
                )
                .use { it.executeUpdate() }
        }
        val named = moves.values.count { it.first.startsWith(NAME_KEY) }
        log.info("ficbook: {} tags given their ids, {} keyed by their names alone", moves.size - named, named)
    }

    /** Every tag key of a name the works have: to its id, or to the name alone; with its name. */
    private fun moves(c: Connection, ids: KeptTagIds): Map<String, Pair<String, String>> {
        val names = HashMap<String, String>()
        c.prepareStatement(
                "SELECT DISTINCT f.value_key, v.name FROM item_facet f LEFT JOIN facet_value v " +
                    "ON v.facet = f.facet AND v.value_key = f.value_key WHERE f.facet = 'tag'"
            )
            .use { s ->
                s.executeQuery().use { r -> while (r.next()) names[r.getString(1)] = r.getString(2) ?: r.getString(1) }
            }
        return names
            .filterKeys { !it.startsWith(NAME_KEY) && !it.all(Char::isDigit) }
            .mapValues { (key, name) ->
                val id = ids.of(key) ?: ids.of(name)
                (id?.toString() ?: (NAME_KEY + name.trim())) to name.trim()
            }
    }

    /** The tag [old] of every work, and what is said of it, keyed [new] — named [name]. */
    private fun move(c: Connection, old: String, new: String, name: String) {
        fun run(sql: String, vararg values: String) =
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { i, v -> s.setString(i + 1, v) }
                s.executeUpdate()
            }
        // A work of both — two names of one tag — keeps one
        run(
            "DELETE FROM item_facet WHERE facet = 'tag' AND value_key = ? AND item_id IN " +
                "(SELECT item_id FROM item_facet WHERE facet = 'tag' AND value_key = ?)",
            old,
            new,
        )
        run("UPDATE item_facet SET value_key = ? WHERE facet = 'tag' AND value_key = ?", new, old)
        run("MERGE INTO facet_value (facet, value_key, name) KEY (facet, value_key) VALUES ('tag', ?, ?)", new, name)
        run("DELETE FROM facet_value WHERE facet = 'tag' AND value_key = ?", old)
        listOf("facet_value_description", "facet_value_vector").forEach { table ->
            run(
                "UPDATE $table SET value_key = ? WHERE facet = 'tag' AND value_key = ? AND NOT EXISTS " +
                    "(SELECT 1 FROM $table t WHERE t.facet = 'tag' AND t.value_key = ?)",
                new,
                old,
                new,
            )
            run("DELETE FROM $table WHERE facet = 'tag' AND value_key = ?", old)
        }
    }

    /**
     * What the kept pages say the ids of the tags' names are, by the name in lower case: of the links of the tags of
     * the works and the lists, of the tags' own pages, of the tags the site's search answered, of their synonyms.
     */
    internal class KeptTagIds {
        private val linked = HashMap<String, MutableSet<Long>>()
        private val found = HashMap<String, MutableSet<Long>>()
        private val synonyms = HashMap<String, MutableSet<Long>>()

        /** The id of the tag [name]: the one the links give, else the one a search found, else a synonym's. */
        fun of(name: String): Long? {
            val key = name.trim().lowercase()
            return listOf(linked, found, synonyms).firstNotNullOfOrNull { it[key]?.singleOrNull() }
        }

        fun read(c: Connection) =
            c.prepareStatement("SELECT url, html FROM captured_page").use { s ->
                s.executeQuery().use { r ->
                    while (r.next()) page(
                        r.getString(1),
                        GZIPInputStream(r.getBinaryStream(2)).use { String(it.readBytes(), Charsets.UTF_8) },
                    )
                }
            }

        /** One kept page: an answer of the search of tags, a tag's own page, a page of works. */
        fun page(url: String, html: String) {
            val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').let { "/$it" }
            if (path == "/tags/search") {
                val tags = runCatching { JSON.readTree(html) }.getOrNull()?.path("data")?.path("tags") ?: return
                tags.forEach { t ->
                    add(found, t.path("title").asString(""), t.path("id").asLong(0L))
                    t.path("synonyms").forEach {
                        add(synonyms, it.path("title").asString(""), it.path("id").asLong(0L))
                    }
                }
                return
            }
            val doc = Jsoup.parse(html)
            TAG_PAGE.matchEntire(path)?.groupValues?.get(1)?.let { id ->
                doc.selectFirst("h1.heading")
                    ?.text()
                    ?.let { TAG_HEADING.find(it)?.groupValues?.get(1) }
                    ?.let {
                        add(found, it, id.toLong())
                    }
            }
            doc.select("a.tag[href]").forEach { a ->
                TAG_PAGE.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { add(linked, a.text(), it.toLong()) }
            }
        }

        private fun add(to: HashMap<String, MutableSet<Long>>, name: String, id: Long) {
            val key = name.trim().lowercase()
            if (key.isNotEmpty() && id > 0) to.getOrPut(key) { HashSet() } += id
        }
    }

    private companion object {
        /** The key of a tag known by its name alone, as the source writes it ([Ficbook.NAME_KEY]). */
        const val NAME_KEY = "name:"

        val JSON = JsonMapper.builder().build()

        /** A tag's own page, "/tags/1682". */
        val TAG_PAGE = Regex("/tags/(\\d+)/?")

        /** The heading of a tag's page: the name between the outer quotes. */
        val TAG_HEADING = Regex("^Метка «(.*)»\\s*-\\s*фанфики")
    }
}
