package db.ficbook

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V5__tags_mended::class.java)

/**
 * THE TAGS V4 KEYED BY THE IDS OF SYNONYMS MENDED (2026-11-18): the ids of the synonyms in the answers of the site's
 * search count the synonyms, not the tags — the synonym "Titfuck" is 1023, the tag "Лихтенштейн" is 1023 — and V4 gave
 * a name found among the synonyms alone the synonym's id, and the parser read the synonyms by their ids, naming the
 * tags of those numbers by them. Every work's tag is keyed anew by the name its page wrote ([KeptTagIds]: a synonym's
 * name is its tag's), a work of two of one tag keeping one; every tag of the works named as the search and its page
 * name it; the synonyms, the names and what is said of the numbers no work has go — the kept pages, read again by the
 * parser's version 18, give the synonyms by their names.
 *
 * Run outside a transaction, a portion of the works at a time, each kept, telling the log how far it is; a work keyed
 * right already is left, so a run broken off is done again from where it stopped.
 */
@Suppress("ClassName")
class V5__tags_mended : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val ids = KeptTagIds().also { it.read(c) }
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            val written = works(c, ids)
            named(c, ids, written)
            c.commit()
        } finally {
            c.autoCommit = autoCommit
        }
    }

    /**
     * Every work's tags keyed by the names their pages wrote, a portion of the works at a time; the names every tag of
     * the works is written with, and how often.
     */
    private fun works(c: Connection, ids: KeptTagIds): Map<String, Map<String, Int>> {
        val items =
            c.prepareStatement("SELECT DISTINCT item_id FROM item_facet WHERE facet = 'tag' ORDER BY item_id").use {
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
            }
        val written = HashMap<String, MutableMap<String, Int>>()
        var moved = 0
        val started = System.nanoTime()
        c.prepareStatement(
                "SELECT item_id, value_key, name FROM item_facet WHERE item_id BETWEEN ? AND ? AND facet = 'tag'"
            )
            .use { read ->
                items.chunked(PORTION).forEachIndexed { i, portion ->
                    if (i % TOLD_EVERY == 0) {
                        val seconds = (System.nanoTime() - started) / NANOS
                        log.info("ficbook: tags of {} of {} works mended in {} s", i * PORTION, items.size, seconds)
                    }
                    read.setString(1, portion.first())
                    read.setString(2, portion.last())
                    val rows =
                        read.executeQuery().use { r ->
                            buildList { while (r.next()) add(Row(r.getString(1), r.getString(2), r.getString(3))) }
                        }
                    moved += portion(c, rows, ids, written)
                    c.commit()
                }
            }
        log.info("ficbook: {} tags of the works keyed anew by their names", moved)
        return written
    }

    /** A work's tag: its key and the name its page wrote. */
    private data class Row(val item: String, val key: String, val name: String?)

    /** The tags of a portion of the works keyed by their names, written in a batch by the key; how many moved. */
    private fun portion(
        c: Connection,
        rows: List<Row>,
        ids: KeptTagIds,
        written: HashMap<String, MutableMap<String, Int>>,
    ): Int {
        val update = mutableListOf<List<String>>()
        val delete = mutableListOf<List<String>>()
        rows
            .groupBy { it.item }
            .forEach { (item, tags) ->
                val wanted = tags.associateWith { t -> t.name?.let(ids::of)?.toString() ?: t.key }
                val kept = tags.filter { wanted.getValue(it) == it.key }.map { it.key }.toHashSet()
                tags.forEach { t ->
                    val key = wanted.getValue(t)
                    if (key != t.key) {
                        if (kept.add(key)) update += listOf(key, item, t.key) else delete += listOf(item, t.key)
                    }
                    if (t.name != null) written.getOrPut(key) { HashMap() }.merge(t.name, 1, Int::plus)
                }
            }
        batch(c, "DELETE FROM item_facet WHERE item_id = ? AND facet = 'tag' AND value_key = ?", delete)
        batch(c, "UPDATE item_facet SET value_key = ? WHERE item_id = ? AND facet = 'tag' AND value_key = ?", update)
        return update.size + delete.size
    }

    /**
     * Every tag of the works named as the search and its page name it, else as the works write it most; the synonyms,
     * and the names and descriptions of the numbers no work has, gone.
     */
    private fun named(c: Connection, ids: KeptTagIds, written: Map<String, Map<String, Int>>) {
        val names = written.mapNotNull { (key, by) ->
            (key.toLongOrNull()?.let(ids::title) ?: by.maxByOrNull { it.value }?.key)?.let { listOf(key, it) }
        }
        batch(c, "MERGE INTO facet_value (facet, value_key, name) KEY (facet, value_key) VALUES ('tag', ?, ?)", names)
        c.prepareStatement("DELETE FROM facet_value_synonym WHERE facet = 'tag'").use { it.executeUpdate() }
        listOf("facet_value", "facet_value_description", "facet_value_vector").forEach { table ->
            val gone =
                c.prepareStatement("SELECT value_key FROM $table WHERE facet = 'tag'").use { s ->
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                } - written.keys
            batch(c, "DELETE FROM $table WHERE facet = 'tag' AND value_key = ?", gone.map { listOf(it) })
        }
        log.info("ficbook: {} tags of the works named by the site", names.size)
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

    private companion object {
        /** The works mended at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        const val NANOS = 1_000_000_000L
    }
}
