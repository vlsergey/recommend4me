package io.github.vlsergey.recommend4me.manner

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class MannerWordsTest {

    @Test
    fun `the words of a text are runs of letters, a hyphen or an apostrophe within, lowercased`() {
        val words = ArrayList<Pair<String, Boolean>>()
        TextWords.forEach("Что-то — don't 42 Гарри, -не- ёж") { w, capital -> words += w to capital }
        assertEquals(
            listOf("что-то" to true, "don't" to false, "гарри" to true, "не" to false, "ёж" to false),
            words,
        )
    }

    @Test
    fun `the words chosen are the most frequent, every text alike, names left out`() {
        val tally = WordTally()
        tally.add(listOf("и и и Гарри сказал"))
        tally.add(listOf("и он сказал Гарри, и Гарри ушёл"))
        tally.add(listOf("Он молчал"))
        // "гарри" is written with a capital always: a name; "он" with one in one place of two
        assertEquals(listOf("и", "он", "молчал"), tally.chosen(3))
    }

    @Test
    fun `a manner is the shares of the words standardised over the texts`() {
        val words = listOf("и", "он")
        val draft = MannerWords(0, words, FloatArray(2), FloatArray(2) { 1f }, 0)
        val first = draft.shares(listOf("и и он нет"))!!
        val second = draft.shares(listOf("и", "он он он"))!!
        assertContentEquals(floatArrayOf(0.5f, 0.25f), first)
        assertContentEquals(floatArrayOf(0.25f, 0.75f), second)
        val fitted = MannerWords.fitted(7, words, listOf(first, second), 2)
        assertContentEquals(floatArrayOf(1f, -1f), fitted.of(listOf("и и он нет")))
        assertNull(fitted.of(listOf("42 — 17")), "a text of no word has no manner")
        val kept = MannerWords.unpack(fitted.id, fitted.texts, fitted.pack())
        assertEquals(words, kept.words)
        assertContentEquals(fitted.of(listOf("и он")), kept.of(listOf("и он")))
    }
}
