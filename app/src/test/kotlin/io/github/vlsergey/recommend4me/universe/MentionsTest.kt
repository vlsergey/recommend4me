package io.github.vlsergey.recommend4me.universe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {

    private val mentions = Mentions(
        mapOf(
            "harry" to listOf("Гарри Поттер", "Harry Potter"),
            "hermione" to listOf("Гермиона Грейнджер", "Hermione Granger"),
            "james" to listOf("Джеймс Поттер"),
            "ron" to listOf("Рон Уизли"),
            "ginny" to listOf("Джинни Уизли", "Джиневра Уизли"),
        ),
    )

    @Test
    fun `a character is found however its name is declined, by a word of the name only it has`() {
        val counts = mentions.count(sequenceOf("Гарри посмотрел на Гермиону. Гермионы не было. Гарри Поттер ушёл к Джинни."))
        assertEquals(2, counts["harry"])
        assertEquals(2, counts["hermione"])
        assertEquals(1, counts["ginny"])
    }

    @Test
    fun `a family name tells nobody by itself`() {
        val counts = mentions.count(sequenceOf("Поттер и Уизли вошли. Джеймс Поттер остался."))
        assertNull(counts["harry"])
        assertNull(counts["ron"])
        assertEquals(1, counts["james"])
    }

    @Test
    fun `characters named in one paragraph are together`() {
        val together = mentions.together(sequenceOf("Гарри поцеловал Гермиону.\n\nРон ел.\nГермиона и Рон спорили."))
        assertEquals(1, together["harry" to "hermione"])
        assertEquals(1, together["hermione" to "ron"])
        assertNull(together["harry" to "ron"])
    }
}
