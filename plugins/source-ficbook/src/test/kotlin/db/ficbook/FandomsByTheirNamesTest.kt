package db.ficbook

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.migration.Context

/**
 * A fandom the dump gave by its name is given the site's address of that name, or of the name spelt in Latin letters
 * when the site has renamed it since; a name of two addresses, or of one made after it, stays.
 */
class FandomsByTheirNamesTest {

    private fun Connection.run(sql: String) = prepareStatement(sql).use { it.executeUpdate() }

    private fun Connection.rows(sql: String): List<List<String?>> =
        prepareStatement(sql).use { s ->
            s.executeQuery().use { r ->
                buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
            }
        }

    @Test
    fun `a fandom given by its name is given the site's address of that name or of the name spelt in Latin`() {
        DriverManager.getConnection("jdbc:h2:mem:fandoms-by-names;DB_CLOSE_DELAY=-1").use { c ->
            c.run(
                "CREATE TABLE item_facet (item_id VARCHAR, facet VARCHAR, value_key VARCHAR, position INT, " +
                    "name VARCHAR, PRIMARY KEY (item_id, facet, value_key))"
            )
            c.run(
                "CREATE TABLE facet_value (facet VARCHAR, value_key VARCHAR, name VARCHAR, " +
                    "PRIMARY KEY (facet, value_key))"
            )
            c.run(
                "INSERT INTO facet_value VALUES ('fandom', 'anime_and_manga/clannad', 'Clannad'), " +
                    "('fandom', 'anime_and_manga/katekyo_hitman_reborn', 'Katekyou Hitman Reborn!'), " +
                    "('fandom', 'anime_and_manga/kiss_', 'Kiss: Hisoyaka na Koi'), " +
                    "('fandom', 'movies_and_tv_series/blizkie_druzjja', 'Близкие друзья'), " +
                    "('fandom', 'movies_and_tv_series/queer_as_folk', 'Близкие друзья'), " +
                    "('fandom', 'name:Clannad', 'Clannad')"
            )
            c.run(
                "INSERT INTO item_facet VALUES ('w1', 'fandom', 'name:Clannad', 0, 'Clannad'), " +
                    "('w2', 'fandom', 'name:Katekyo Hitman Reborn!', 0, 'Katekyo Hitman Reborn!'), " +
                    "('w3', 'fandom', 'name:KISS', 0, 'KISS'), " +
                    "('w4', 'fandom', 'name:Близкие друзья', 0, 'Близкие друзья'), " +
                    "('w5', 'fandom', 'anime_and_manga/clannad', 0, 'Clannad'), " +
                    "('w5', 'fandom', 'name:Clannad', 1, 'Clannad'), " +
                    "('w6', 'fandom', 'name:Игра Престолов', 0, 'Игра Престолов'), " +
                    "('w7', 'fandom', 'movies_and_tv_series/igra_prestolov', 0, 'Игра престолов')"
            )
            V6__fandoms_by_their_names()
                .migrate(
                    object : Context {
                        override fun getConfiguration(): Configuration = error("not read")

                        override fun getConnection() = c
                    }
                )
            assertEquals(
                listOf(
                    listOf("w1", "anime_and_manga/clannad", "Clannad"),
                    listOf("w2", "anime_and_manga/katekyo_hitman_reborn", "Katekyo Hitman Reborn!"),
                    listOf("w3", "name:KISS", "KISS"),
                    listOf("w4", "name:Близкие друзья", "Близкие друзья"),
                    listOf("w5", "anime_and_manga/clannad", "Clannad"),
                    listOf("w6", "movies_and_tv_series/igra_prestolov", "Игра Престолов"),
                    listOf("w7", "movies_and_tv_series/igra_prestolov", "Игра престолов"),
                ),
                c.rows("SELECT item_id, value_key, name FROM item_facet ORDER BY item_id, value_key"),
            )
            // The key given by the name keeps its name: the fandom it is bound to binds the site's of that name
            assertEquals(
                listOf(listOf("Clannad")),
                c.rows("SELECT name FROM facet_value WHERE value_key = 'name:Clannad'"),
            )
        }
    }
}
