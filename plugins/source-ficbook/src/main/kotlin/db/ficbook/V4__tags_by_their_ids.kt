package db.ficbook

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V4__tags_by_their_ids::class.java)

/**
 * A TAG OF FICBOOK IS THE SITE'S ID OF ITS PAGE, ITS NAME AND DESCRIPTION BOUND TO IT (user, 2026-11-18: "a tag is a
 * numeric id to the site; if you do not know the id, you cannot link to the tag's page"). The tags were keyed by their
 * names in lower case — of the site's pages and of the dump alike — and are given their ids, of every work at once: a
 * name is one tag of the site, its id what the kept pages say of it ([KeptTagIds]) — the links of the tags of the works
 * and the lists first, then the tags the site's search of tags answered, then the tags they are synonyms of. A name of
 * no id found is keyed by the name alone ("name:"), asked of the site's search later. The other names the site files
 * under a tag are taken away: the kept answers of the search, read again by the parser's version 17, give them by their
 * ids.
 *
 * Run outside a transaction, a portion of the works at a time — by the range of their ids, read in the order of the
 * key, their new keys worked out in memory, written by the key in a batch and kept (user, 2026-11-18: the first form, a
 * statement of every tag over the 6.9 million, held H2's undo log of a tag of half a million works in the memory); a
 * key already an id or a name is left — no name of a tag of the works is all digits (2026-11-18) — so the migration
 * broken off is done again from where it stopped.
 */
@Suppress("ClassName")
class V4__tags_by_their_ids : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val ids = KeptTagIds().also { it.read(c) }
        val moves = moves(c, ids)
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            if (moves.isNotEmpty()) works(c, moves.mapValues { it.value.first })
            moves.forEach { (old, new) -> said(c, old, new.first, new.second) }
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
            c.commit()
        } finally {
            c.autoCommit = autoCommit
        }
        val named = moves.values.count { it.first.startsWith(NAME_KEY) }
        log.info("ficbook: {} tags given their ids, {} keyed by their names alone", moves.size - named, named)
    }

    /** Every tag key of a name the works have: to its id, or to the name alone; with its name. */
    private fun moves(c: Connection, ids: KeptTagIds): Map<String, Pair<String, String>> {
        val names = HashMap<String, String>()
        c.prepareStatement("SELECT value_key, name FROM facet_value WHERE facet = 'tag'").use { s ->
            s.executeQuery().use { r -> while (r.next()) names[r.getString("value_key")] = r.getString("name") }
        }
        val keys =
            c.prepareStatement("SELECT DISTINCT value_key FROM item_facet WHERE facet = 'tag'").use { s ->
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
            }
        return keys
            .filterNot { it.startsWith(NAME_KEY) || it.all(Char::isDigit) }
            .associateWith { key ->
                val name = (names[key] ?: key).trim()
                ((ids.of(key) ?: ids.of(name))?.toString() ?: (NAME_KEY + name)) to name
            }
    }

    /** The tags of every work keyed anew by [moves], a portion of the works at a time, each kept as it is done. */
    private fun works(c: Connection, moves: Map<String, String>) {
        val items =
            c.prepareStatement("SELECT DISTINCT item_id FROM item_facet WHERE facet = 'tag' ORDER BY item_id").use {
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
            }
        val read =
            c.prepareStatement(
                "SELECT item_id, value_key FROM item_facet WHERE item_id BETWEEN ? AND ? AND facet = 'tag' " +
                    "ORDER BY item_id, position"
            )
        val started = System.nanoTime()
        read.use {
            items.chunked(PORTION).forEachIndexed { i, portion ->
                // A long start followed in the log (2026-11-18: the file locked by the migration, nothing else tells)
                if (i % TOLD_EVERY == 0) told(i * PORTION, items.size, started)
                read.setString(1, portion.first())
                read.setString(2, portion.last())
                val rows =
                    read.executeQuery().use { r ->
                        buildList { while (r.next()) add(r.getString(1) to r.getString(2)) }
                    }
                portion(c, rows.groupBy({ it.first }, { it.second }), moves)
                c.commit()
            }
        }
    }

    private fun told(done: Int, of: Int, started: Long) =
        log.info("ficbook: tags of {} of {} works keyed anew in {} s", done, of, (System.nanoTime() - started) / NANOS)

    /**
     * The tags of the works [tags] keyed anew by [moves], written in a batch by the key: a work of two names of one tag
     * keeps one.
     */
    private fun portion(c: Connection, tags: Map<String, List<String>>, moves: Map<String, String>) {
        val update = mutableListOf<List<String>>()
        val delete = mutableListOf<List<String>>()
        tags.forEach { (item, keys) ->
            val kept = keys.filterNot(moves::containsKey).toHashSet()
            keys.filter(moves::containsKey).forEach { old ->
                val new = moves.getValue(old)
                if (kept.add(new)) update += listOf(new, item, old) else delete += listOf(item, old)
            }
        }
        batch(c, "DELETE FROM item_facet WHERE item_id = ? AND facet = 'tag' AND value_key = ?", delete)
        batch(c, "UPDATE item_facet SET value_key = ? WHERE item_id = ? AND facet = 'tag' AND value_key = ?", update)
    }

    /** The statement [sql] run of every row of [values] in one batch. */
    private fun batch(c: Connection, sql: String, values: List<List<String>>) {
        if (values.isEmpty()) return
        c.prepareStatement(sql).use { s ->
            values.forEach { row ->
                row.forEachIndexed { i, v -> s.setString(i + 1, v) }
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    /** What is said of the tag [old] — its name, its description, its name's vector — said of [new], named [name]. */
    private fun said(c: Connection, old: String, new: String, name: String) {
        fun run(sql: String, vararg values: String) =
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { i, v -> s.setString(i + 1, v) }
                s.executeUpdate()
            }
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

    private companion object {
        /** The works keyed anew at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        const val NANOS = 1_000_000_000L

        /** The key of a tag known by its name alone, as the source writes it ([Ficbook.NAME_KEY]). */
        const val NAME_KEY = "name:"
    }
}
