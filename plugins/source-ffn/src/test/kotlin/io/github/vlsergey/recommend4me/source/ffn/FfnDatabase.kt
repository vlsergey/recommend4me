package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.source.MemorySourceContext
import io.github.vlsergey.recommend4me.source.SourceContext
import java.sql.Connection
import java.sql.DriverManager
import org.h2.tools.RunScript
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

/**
 * The plugin's own table of the fandoms in a database of memory, a test's own: the application's data in a memory
 * context, [of] it given this database.
 */
class FfnDatabase : AutoCloseable {
    private val connection: Connection =
        DriverManager.getConnection("jdbc:h2:mem:ffn-${System.nanoTime()};DB_CLOSE_DELAY=-1").also { c ->
            javaClass.getResourceAsStream("/db/ffn/V1__fandoms.sql")!!.reader().use { RunScript.execute(c, it) }
        }
    private val dsl: DSLContext = DSL.using(connection, SQLDialect.H2)

    /** The memory [context] with this database. */
    fun of(context: MemorySourceContext): SourceContext =
        object : SourceContext by context {
            override val database: DSLContext
                get() = dsl
        }

    override fun close() = connection.close()
}
