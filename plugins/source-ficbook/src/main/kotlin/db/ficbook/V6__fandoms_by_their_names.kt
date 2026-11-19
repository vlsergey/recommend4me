package db.ficbook

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V6__fandoms_by_their_names::class.java)

/**
 * A FANDOM THE DUMP GAVE BY ITS NAME ALONE IS THE SITE'S FANDOM OF THAT NAME (owner, 2026-11-19: "go through the
 * catalogue by GET"): the dump's works have their fandom as "name:Clannad", and the site's pages — the works, the
 * fandoms' own pages, the catalogue of the fandoms of every group — name the site's fandoms by their addresses,
 * "anime_and_manga/clannad" named «Clannad». A name one address of the site has, as the page writes it, is that fandom:
 * the works of the name are given the address. A name of two addresses stays as it is.
 *
 * A name no address has may be one the site has renamed since the dump: the address is the fandom's name as it was
 * made, its letters spelt in Latin — «Katekyo Hitman Reborn!» is "anime_and_manga/katekyo_hitman_reborn", named
 * «Katekyou Hitman Reborn!» now. The one address whose part after the group is the name so spelt, the marks at its end
 * left out, is that fandom; an address longer than the name spelt — "anime_and_manga/kiss_" of «KISS» — is another
 * fandom of that name, made after, and is not taken.
 *
 * The name of the key given by the name stays in the dictionary of the values: the normalized fandom it is bound to is
 * the one the site's fandom is bound to by that name as soon as the application starts, and the key of no works then
 * goes, a twin of the one the works have ([UnusedFandoms][io.github.vlsergey.recommend4me.universe.UnusedFandoms]).
 *
 * Run outside a transaction, a portion of the works at a time — by the range of their ids — each kept as it is done,
 * the log told how far it is: broken off, it is done again from where it stopped, the keys moved being no names.
 */
@Suppress("ClassName")
class V6__fandoms_by_their_names : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val moves = moves(c)
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            if (moves.isNotEmpty()) works(c, moves)
            c.commit()
        } finally {
            c.autoCommit = autoCommit
        }
        log.info("ficbook: {} fandoms given by their names given the addresses of the site's", moves.size)
    }

    /**
     * Every key of a name the works have to the one address of the site of that name, else to the one address of the
     * name spelt in Latin letters.
     */
    private fun moves(c: Connection): Map<String, String> {
        val addresses = HashMap<String, MutableSet<String>>()
        c.prepareStatement("SELECT value_key, name FROM facet_value WHERE facet = 'fandom' AND name IS NOT NULL").use {
            it.executeQuery().use { r ->
                while (r.next()) {
                    val key = r.getString(1)
                    val name = r.getString(2).trim()
                    if (!key.startsWith(NAME_KEY) && name.isNotEmpty()) addresses.getOrPut(name) { HashSet() } += key
                }
            }
        }
        val keys =
            keys(c, "SELECT value_key FROM facet_value WHERE facet = 'fandom' AND value_key NOT LIKE 'name:%'") +
                keys(
                    c,
                    "SELECT DISTINCT value_key FROM item_facet WHERE facet = 'fandom' AND value_key NOT LIKE 'name:%'",
                )
        val spelt = HashMap<String, MutableSet<String>>()
        keys
            .filter { it.count { ch -> ch == '/' } == 1 }
            .forEach { spelt.getOrPut(it.substringAfter('/').trimEnd('_')) { HashSet() } += it }
        val named =
            keys(c, "SELECT DISTINCT value_key FROM item_facet WHERE facet = 'fandom' AND value_key LIKE 'name:%'")
        return named
            .mapNotNull { key ->
                val name = key.removePrefix(NAME_KEY).trim()
                val byName = addresses[name]
                if (byName != null) byName.singleOrNull()?.let { key to it }
                else {
                    val latin = latin(name)
                    spelt[
                            latin.trimEnd('_').ifEmpty {
                                return@mapNotNull null
                            }]
                        ?.filter { it.substringAfter('/').length <= latin.length }
                        ?.singleOrNull()
                        ?.let { key to it }
                }
            }
            .toMap()
    }

    /** The keys the query [sql] gives. */
    private fun keys(c: Connection, sql: String): Set<String> =
        c.prepareStatement(sql).use { it.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1)) } } }

    /**
     * The fandom's name spelt as the site spelt it in an address it made: lower case, the Russian letters in Latin
     * ones, every sign but a Latin letter or a digit "_", those at its start left out.
     */
    private fun latin(name: String): String =
        name
            .lowercase()
            .map { ch -> LATIN[ch] ?: if (ch in 'a'..'z' || ch in '0'..'9') ch.toString() else "_" }
            .joinToString("")
            .trimStart('_')

    /** The fandoms of every work of a name keyed anew by [moves], a portion of the works at a time, each kept. */
    private fun works(c: Connection, moves: Map<String, String>) {
        val items =
            c.prepareStatement(
                    "SELECT DISTINCT item_id FROM item_facet WHERE facet = 'fandom' AND value_key LIKE 'name:%' " +
                        "ORDER BY item_id"
                )
                .use { it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
        val read =
            c.prepareStatement(
                "SELECT item_id, value_key FROM item_facet WHERE item_id BETWEEN ? AND ? AND facet = 'fandom'"
            )
        val started = System.nanoTime()
        read.use {
            items.chunked(PORTION).forEachIndexed { i, portion ->
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
        told(items.size, items.size, started)
    }

    private fun told(done: Int, of: Int, started: Long) =
        log.info(
            "ficbook: fandoms of {} of {} works keyed anew in {} s",
            done,
            of,
            (System.nanoTime() - started) / NANOS,
        )

    /**
     * The fandoms of the works [fandoms] keyed anew by [moves], written in a batch: a work that has the address already
     * keeps it once. The name each was written by on the work's page stays.
     */
    private fun portion(c: Connection, fandoms: Map<String, List<String>>, moves: Map<String, String>) {
        val update = mutableListOf<List<String>>()
        val delete = mutableListOf<List<String>>()
        fandoms.forEach { (item, keys) ->
            val kept = keys.filterNot(moves::containsKey).toHashSet()
            keys.filter(moves::containsKey).forEach { old ->
                val new = moves.getValue(old)
                if (kept.add(new)) update += listOf(new, item, old) else delete += listOf(item, old)
            }
        }
        batch(c, "DELETE FROM item_facet WHERE item_id = ? AND facet = 'fandom' AND value_key = ?", delete)
        batch(
            c,
            "UPDATE item_facet SET value_key = ? WHERE item_id = ? AND facet = 'fandom' AND value_key = ?",
            update,
        )
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
        /** The works keyed anew at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        const val NANOS = 1_000_000_000L

        /** The key of a fandom known by its name alone, as the source writes it ([Ficbook.NAME_KEY]). */
        const val NAME_KEY = "name:"

        /** The Russian letters as the site's addresses spell them. */
        val LATIN: Map<Char, String> =
            "абвгдезиклмнопрстуфы".zip("abvgdeziklmnoprstufy").associate { (r, l) -> r to l.toString() } +
                mapOf(
                    'ё' to "e",
                    'ж' to "zh",
                    'й' to "j",
                    'х' to "h",
                    'ц' to "c",
                    'ч' to "ch",
                    'ш' to "sh",
                    'щ' to "sch",
                    'ъ' to "",
                    'ь' to "j",
                    'э' to "e",
                    'ю' to "ju",
                    'я' to "ja",
                )
    }
}
