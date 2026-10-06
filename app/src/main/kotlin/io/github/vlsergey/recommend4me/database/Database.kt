package io.github.vlsergey.recommend4me.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(Database::class.java)

/**
 * One H2 file — the data of one level of one source or content type — with its own pool of
 * connections and its own migrations: the application's (`classpath:db/<kind>`), and for a source
 * database the source's own ([pluginMigrations]) with a history table of their own.
 */
class Database(
    val name: String,
    /** The file without its `.mv.db`. */
    val file: Path,
    kind: String,
    pluginMigrations: List<String> = emptyList(),
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : AutoCloseable {

    private val pool = HikariDataSource(HikariConfig().apply {
        // A writer waits for a row another transaction holds up to 10 s (2 s by default)
        jdbcUrl = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
        username = "sa"
        password = ""
        poolName = name
        maximumPoolSize = POOL
        minimumIdle = 1
    })

    val dsl: DSLContext = DSL.using(pool, SQLDialect.H2)

    init {
        migrate(listOf("classpath:db/$kind"), "flyway_schema_history", classLoader)
        if (pluginMigrations.isNotEmpty()) migrate(pluginMigrations, "plugin_schema_history", classLoader)
    }

    private fun migrate(locations: List<String>, table: String, classLoader: ClassLoader) {
        val result = Flyway.configure(classLoader)
            .dataSource(pool)
            .locations(*locations.toTypedArray())
            .table(table)
            .baselineOnMigrate(false)
            .load()
            .migrate()
        if (result.migrationsExecuted > 0) log.info("{}: {} migrations of {} applied", name, result.migrationsExecuted, locations)
    }

    override fun close() = pool.close()

    companion object {
        private const val POOL = 6
    }
}
