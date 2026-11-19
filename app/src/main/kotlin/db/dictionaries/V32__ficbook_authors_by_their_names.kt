package db.dictionaries

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V32__ficbook_authors_by_their_names::class.java)

/**
 * THE AUTHORS OF FICBOOK THE PLACES ON OTHER SITES GAVE BY THEIR NAMES ARE THE AUTHORS THE DUMP GAVE BY THEM (owner,
 * 2026-11-19): the places an author's page of fanfics.me lists give ficbook's pages of the older kind, by the account's
 * name — "https://ficbook.net/authors/Мышилла" — and ficbook's key of the author was that name itself, neither a key of
 * the site (a number, a UUID) nor the key of the author known by the name ("name:Мышилла", as the dump gave them): the
 * person of a place and the person of the dump's works were two authors of the site. Every such key is the author known
 * by the name now — the places and the marks of why an author was merged naming it so — and where the dump gave that
 * name, the two are one: the dump's normalized author merged into the place's, as a place merges them, unless the user
 * told them apart. Two names the pages of fanfics.me gave misread — UTF-8 read as Windows-1251, «РЎРѕР»РѕС…Р°» of
 * «Солоха» — are read as written ([MISREAD]).
 *
 * What a merge of authors tells the application as it is done
 * ([io.github.vlsergey.recommend4me.author.AuthorRepository] .merge) is not told here: a few authors of no works of
 * their own on the site, the manners of the works regrouped by the next change.
 */
@Suppress("ClassName")
class V32__ficbook_authors_by_their_names : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val c = context.connection
        val now = Timestamp.from(Instant.now())
        val keys = byName(c)
        var merged = 0
        keys.forEach { (key, normalized) ->
            val named = NAME_KEY + (MISREAD[key] ?: key)
            renamed(c, key, named)
            val dump = normalizedOf(c, named)
            if (dump == null) {
                update(c, "UPDATE site_author SET author = ? WHERE source = '$SOURCE' AND author = ?", named, key)
            } else {
                update(c, "DELETE FROM site_author WHERE source = '$SOURCE' AND author = ?", key)
                if (dump != normalized && !toldApart(c, dump, normalized)) {
                    merge(c, dump, normalized, ownerOf(c, named), now)
                    merged++
                }
            }
        }
        log.info("{}: {} authors given by their names keyed so, {} merged with the dump's", SOURCE, keys.size, merged)
    }

    /** The keys of the authors of the source that are neither the site's keys nor names: key to normalized author. */
    private fun byName(c: Connection): Map<String, Long> =
        c.prepareStatement("SELECT author, normalized FROM site_author WHERE source = '$SOURCE'").use { s ->
            s.executeQuery().use { r ->
                buildMap {
                    while (r.next()) {
                        val key = r.getString(1)
                        if (!key.startsWith(NAME_KEY) && !SITE_KEY.matches(key)) put(key, r.getLong(2))
                    }
                }
            }
        }

    /** The places and the marks of a merge naming the author [key] name it [named]. */
    private fun renamed(c: Connection, key: String, named: String) {
        update(
            c,
            "UPDATE site_author_place SET place_author = ? WHERE place_source = '$SOURCE' AND place_author = ?",
            named,
            key,
        )
        update(c, "UPDATE site_author_place SET author = ? WHERE source = '$SOURCE' AND author = ?", named, key)
        update(
            c,
            "UPDATE site_author SET merged_by_author = ? WHERE merged_by_source = '$SOURCE' AND merged_by_author = ?",
            named,
            key,
        )
    }

    /** The normalized author the author [key] of the source is bound to; null of none. */
    private fun normalizedOf(c: Connection, key: String): Long? =
        c.prepareStatement("SELECT normalized FROM site_author WHERE source = '$SOURCE' AND author = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { r -> if (r.next()) r.getLong(1) else null }
        }

    /** Whether the user told the two normalized authors apart, or said they are partly one. */
    private fun toldApart(c: Connection, a: Long, b: Long): Boolean =
        c.prepareStatement("SELECT 1 FROM normalized_author_distinct WHERE first_author = ? AND other_author = ?").use {
            it.setLong(1, minOf(a, b))
            it.setLong(2, maxOf(a, b))
            it.executeQuery().use { r -> r.next() }
        }

    /** The author of a site whose page lists the author [named] of the source as a place of theirs; null of none. */
    private fun ownerOf(c: Connection, named: String): Pair<String, String>? =
        c.prepareStatement(
                "SELECT source, author FROM site_author_place WHERE place_source = '$SOURCE' AND place_author = ? " +
                    "ORDER BY said_at"
            )
            .use { s ->
                s.setString(1, named)
                s.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getString(2) else null }
            }

    /**
     * The normalized author [from] merged into [into] as the application merges two by a place ([owner] the author
     * whose page lists it; by the name when none does): its authors of the sites marked so and bound to [into], its
     * pairs told apart moved to [into], its entries of the catalogues besides those of [into], and [from] gone.
     */
    private fun merge(c: Connection, from: Long, into: Long, owner: Pair<String, String>?, now: Timestamp) {
        update(
            c,
            "UPDATE site_author SET merged_by = ?, merged_by_source = ?, merged_by_author = ? " +
                "WHERE normalized = ? AND merged_by IS NULL",
            if (owner != null) PLACE else NAME,
            owner?.first,
            owner?.second,
            from,
        )
        update(c, "UPDATE site_author SET normalized = ?, bound_at = ? WHERE normalized = ?", into, now, from)
        update(
            c,
            "INSERT INTO normalized_author_distinct (first_author, other_author, said_at, partly) " +
                "SELECT LEAST(o, ?), GREATEST(o, ?), ?, partly FROM (" +
                "SELECT CASE WHEN first_author = ? THEN other_author ELSE first_author END AS o, partly " +
                "FROM normalized_author_distinct WHERE first_author = ? OR other_author = ?) d " +
                "WHERE o <> ? AND NOT EXISTS (SELECT 1 FROM normalized_author_distinct e " +
                "WHERE e.first_author = LEAST(d.o, ?) AND e.other_author = GREATEST(d.o, ?))",
            into,
            into,
            now,
            from,
            from,
            from,
            into,
            into,
            into,
        )
        update(
            c,
            "INSERT INTO normalized_author_entry (author, catalogue, entry, bound_at) " +
                "SELECT ?, catalogue, entry, bound_at FROM normalized_author_entry e WHERE author = ? " +
                "AND NOT EXISTS (SELECT 1 FROM normalized_author_entry i " +
                "WHERE i.author = ? AND i.catalogue = e.catalogue AND i.entry = e.entry)",
            into,
            from,
            into,
        )
        update(c, "DELETE FROM normalized_author_distinct WHERE first_author = ? OR other_author = ?", from, from)
        update(c, "DELETE FROM normalized_author_entry WHERE author = ?", from)
        update(c, "DELETE FROM normalized_author WHERE id = ?", from)
    }

    /** The statement [sql] run once with the [values], in their order. */
    private fun update(c: Connection, sql: String, vararg values: Any?) =
        c.prepareStatement(sql).use { s ->
            values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
            s.executeUpdate()
        }

    private companion object {
        const val SOURCE = "ficbook"

        /** The key of an author known by the name alone, as the source writes it. */
        const val NAME_KEY = "name:"

        /** The marks of why an author of a site was merged: a place, a name. */
        const val PLACE = "PLACE"
        const val NAME = "NAME"

        /** The site's own key of an author: a number, or of the newer accounts a UUID. */
        val SITE_KEY = Regex("\\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * The names the pages of fanfics.me gave misread, UTF-8 read as Windows-1251: the name misread to the name — of
         * the two found, «Солоха и Эйзенхайм» and «Мятная панда».
         */
        val MISREAD: Map<String, String> =
            listOf("Солоха и Эйзенхайм", "Мятная панда").associateBy {
                String(it.toByteArray(Charsets.UTF_8), charset("windows-1251"))
            }
    }
}
