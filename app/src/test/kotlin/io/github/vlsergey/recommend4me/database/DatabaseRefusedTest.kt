package io.github.vlsergey.recommend4me.database

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** A computation of the facets is refused the database: a connection asked for in it fails, naming it. */
class DatabaseRefusedTest {

    @TempDir lateinit var folder: Path

    @Test
    fun `a connection asked for during a computation that reads nothing fails at once, and is given after it`() {
        Database("test", folder.resolve("refused"), "ratings").use { db ->
            assertEquals(1, db.dsl.fetchValue("SELECT 1"))
            val refused =
                assertFailsWith<IllegalStateException> {
                    DatabaseRefused.during("the chances of test/tags") { db.dsl.fetchValue("SELECT 1") }
                }
            assertTrue("the chances of test/tags" in refused.message.orEmpty(), refused.message)
            // Nested: the outer computation refused again once the inner one is over
            assertFailsWith<IllegalStateException> {
                DatabaseRefused.during("outer") {
                    DatabaseRefused.during("inner") {}
                    db.dsl.fetchValue("SELECT 1")
                }
            }
            assertEquals(1, db.dsl.fetchValue("SELECT 1"))
        }
    }
}
