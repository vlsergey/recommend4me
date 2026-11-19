package io.github.vlsergey.recommend4me.duplicate

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/**
 * EVERY NAME OF A ROW OF THE WINDOW OF A PAIR THE SERVER SENDS HAS ITS WORDS ([PairFields.LABELS]): the web interface
 * keeps them (`frontend/src/duplicate/duplicate-i18n.en.json`; its compiler checks the other languages against the
 * English). A name the interface lacks would be shown as its key.
 */
class PairLabelsTest {

    private val file = Path.of("../frontend/src/duplicate/duplicate-i18n.en.json").toAbsolutePath().normalize()

    @Test
    fun `every name of a row the server sends is among the interface's words`() {
        assertTrue(Files.isRegularFile(file), "no $file")
        @Suppress("UNCHECKED_CAST")
        val words = JsonMapper.builder().build().readValue(file.toFile(), Map::class.java).keys as Set<String>
        val missing = PairFields.LABELS.map { it.removePrefix("duplicate:") }.filter { it !in words }
        assertTrue(missing.isEmpty(), "missing in $file: $missing")
    }
}
