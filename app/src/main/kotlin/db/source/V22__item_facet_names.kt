package db.source

import java.sql.Connection
import java.sql.PreparedStatement
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V22__item_facet_names::class.java)

/**
 * Every value of every work written before the works kept their values' names (V21) given the name the source has of
 * its key now — what the site said last, of a dump's work what the dump said. A portion of the works at a time, by the
 * range of their ids, each kept as it is done: of ficbook's ten million values of 1.4 million works no transaction
 * holds them all; a value named already is left, so a migration broken off is done again from where it stopped.
 */
@Suppress("ClassName")
class V22__item_facet_names : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val names = names(c)
        if (names.isEmpty()) return
        val items =
            c.createStatement().use { s ->
                s.executeQuery("SELECT DISTINCT item_id FROM item_facet ORDER BY item_id").use { r ->
                    buildList { while (r.next()) add(r.getString(1)) }
                }
            }
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            val started = System.nanoTime()
            items.chunked(PORTION).forEachIndexed { i, portion ->
                // A long start followed in the log (2026-11-18: the file locked by the migration, nothing else tells)
                if (i % TOLD_EVERY == 0) {
                    val seconds = (System.nanoTime() - started) / NANOS
                    log.info("values of {} of {} works named in {} s", i * PORTION, items.size, seconds)
                }
                name(c, portion.first(), portion.last(), names)
                c.commit()
            }
        } finally {
            c.autoCommit = autoCommit
        }
    }

    /** The name of every value of every facet: facet to key to name. */
    private fun names(c: Connection): Map<String, Map<String, String>> {
        val out = HashMap<String, HashMap<String, String>>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT facet, value_key, name FROM facet_value").use { r ->
                while (r.next()) out.getOrPut(r.getString("facet")) { HashMap() }[r.getString("value_key")] =
                    r.getString("name")
            }
        }
        return out
    }

    /** The values of the works from [first] to [last] of no name yet given the names [names] has of them. */
    private fun name(c: Connection, first: String, last: String, names: Map<String, Map<String, String>>) {
        val values =
            c.prepareStatement(
                    "SELECT item_id, facet, value_key FROM item_facet WHERE item_id BETWEEN ? AND ? AND name IS NULL"
                )
                .use { s ->
                    s.set(first, last)
                    s.executeQuery().use { r ->
                        buildList {
                            while (r.next()) add(
                                listOf(r.getString("item_id"), r.getString("facet"), r.getString("value_key"))
                            )
                        }
                    }
                }
        val update = "UPDATE item_facet SET name = ? WHERE item_id = ? AND facet = ? AND value_key = ?"
        c.prepareStatement(update).use { s ->
            values.forEach { (item, facet, key) ->
                val name = names[facet]?.get(key) ?: return@forEach
                s.set(name, item, facet, key)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    /** The statement's parameters, in their order. */
    private fun PreparedStatement.set(vararg values: String) = values.forEachIndexed { i, v -> setString(i + 1, v) }

    private companion object {
        /** The works named at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        const val NANOS = 1_000_000_000L
    }
}
