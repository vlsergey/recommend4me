package io.github.vlsergey.recommend4me.database

import java.io.StringReader
import javax.sql.DataSource
import org.h2.util.ScriptReader
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE SCHEMA OF A KIND OF DATABASE AS ITS MIGRATIONS LEAVE IT, in one script: `schema/<kind>.sql`. A new file is made
 * by it at once, its history of migrations begun at the last of them — not by every migration since the first, a step
 * after a step (user, 2026-11-18: "an empty database gets the schema of schema.sql alone; an existing one, the
 * migrations"). The script is the one the migrations make, kept so by a test of every kind (`SchemaTest`), which writes
 * it anew when a migration changes it.
 */
object Schema {
    /** Every kind of database of the application: the folders of its migrations, `db/<kind>`. */
    val KINDS = listOf("app", "source", "corrections", "ratings", "model", "dictionaries", "preferences")

    /** The script of the [kind], its placeholders written with their values; null when the kind has none. */
    fun of(kind: String, classLoader: ClassLoader, placeholders: Map<String, String>): String? =
        classLoader.getResource("schema/$kind.sql")?.readText()?.let { script ->
            placeholders.entries.fold(script) { s, (key, value) -> s.replace("\${$key}", value) }
        }

    /** Whether the database has no table of its own yet: a file just made. */
    fun empty(dsl: DSLContext): Boolean =
        !dsl.fetchExists(
            DSL.selectOne().from("INFORMATION_SCHEMA.TABLES").where(DSL.field("TABLE_SCHEMA").eq("PUBLIC"))
        )

    /** Runs the [script], a statement after a statement, in one transaction. */
    fun run(pool: DataSource, script: String) {
        pool.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                val reader = ScriptReader(StringReader(script))
                generateSequence { reader.readStatement() }.filter { it.isNotBlank() }.forEach { s.execute(it) }
            }
            c.commit()
        }
    }
}
