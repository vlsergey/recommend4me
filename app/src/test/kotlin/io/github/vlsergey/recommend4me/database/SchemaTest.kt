package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.part.PartStore
import io.github.vlsergey.recommend4me.part.V19__parts_out_of_database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir

/**
 * THE SCHEMA OF EVERY KIND OF DATABASE IS THE ONE ITS MIGRATIONS MAKE ([Schema]): a file made by every migration since
 * the first holds what `schema/<kind>.sql` makes a new file of at once. A migration that changes the schema and a
 * script not changed with it fail here — the script written anew from the migrations, to be committed with them. And a
 * file made by the script is as the migrations leave it, its migrations done.
 */
class SchemaTest {

    @TempDir lateinit var folder: Path

    private val scripts = Path.of("src/main/resources/schema")

    @Test
    fun `every kind of database is made by its schema as by its migrations`() {
        val chained = Schema.KINDS.associateWith { chained(it) }
        val wrong = chained.filter { (kind, script) ->
            val file = scripts.resolve("$kind.sql")
            if (Files.exists(file) && Files.readString(file).replace("\r\n", "\n") == script) return@filter false
            Files.createDirectories(scripts)
            Files.writeString(file, script)
            true
        }
        if (wrong.isNotEmpty())
            fail(
                "The schemas of ${wrong.keys} differ from what their migrations make: " +
                    "written anew in $scripts, commit them"
            )
        chained.forEach { (kind, script) ->
            Database(kind, folder.resolve("made-$kind"), kind, placeholders = PLACEHOLDERS, code = code(kind)).use {
                // The same statements: H2 writes the constraints in the order it numbered them
                assertEquals(script.lines().sorted(), script(it.dsl).lines().sorted(), kind)
            }
        }
    }

    /** The script of a file of the [kind] made by every migration since the first. */
    private fun chained(kind: String): String =
        Database(
                kind,
                folder.resolve("chained-$kind"),
                kind,
                placeholders = PLACEHOLDERS,
                code = code(kind),
                bySchema = false,
            )
            .use { script(it.dsl) }

    /** The store of the texts the migration of the source's texts moves them into: one, opened once. */
    private val parts by lazy { PartStore(folder.resolve("parts")) }

    @AfterEach fun close() = parts.close()

    /** The migrations of the [kind] written in code, as the application gives them ([Databases]). */
    private fun code(kind: String) = if (kind == "source") listOf(V19__parts_out_of_database(parts)) else emptyList()

    private companion object {
        /** The placeholders kept as they are: the script writes them with their values when a file is made. */
        val PLACEHOLDERS = mapOf("source" to "\${source}")

        /**
         * The schema of a database as H2 writes it: its tables, keys, indexes and sequences, no data, no user, no
         * history of the migrations.
         */
        fun script(db: DSLContext): String =
            db.fetch("SCRIPT NODATA NOPASSWORDS NOSETTINGS")
                .map { it.get(0, String::class.java).replace("\r\n", "\n") }
                .filter { !it.startsWith("--") && !it.startsWith("CREATE USER") && "schema_history" !in it }
                .joinToString("\n", postfix = "\n")
    }
}
