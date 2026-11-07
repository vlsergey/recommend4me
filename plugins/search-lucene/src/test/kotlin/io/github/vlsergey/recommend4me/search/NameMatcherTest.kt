package io.github.vlsergey.recommend4me.search

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A matcher of the names of a base but some values finds what a matcher of those names alone finds, a word of a value
 * left out naming another alone; two words at one place both read.
 */
class NameMatcherTest {

    private val names =
        mapOf(
            "harry" to listOf("Гарри Поттер", "Harry Potter"),
            "james" to listOf("Джеймс Поттер"),
            "lily" to listOf("Лили Поттер", "Лили Эванс"),
            "ron" to listOf("Рон Уизли"),
            "ginny" to listOf("Джинни Уизли", "Джиневра Уизли"),
            "hermione" to listOf("Гермиона Грейнджер", "Hermione Granger"),
            "saw" to listOf("Со Геррера"),
            "rick" to listOf("Рик Джонс", "Нова"),
        )

    private val stems = NameStems()

    /** Words of the stems of [texts] one after another, some with a capital: a text as the index has it. */
    private fun words(random: Random, texts: List<String>): List<NamedWord> {
        val out = ArrayList<NamedWord>()
        var at = 0
        texts.forEach { text ->
            stems.of(text, "ru").forEach { stem -> out += NamedWord(stem, at++, random.nextInt(3) > 0) }
            at += random.nextInt(2)
        }
        return out
    }

    private fun found(matcher: NameMatcher, words: List<NamedWord>) =
        matcher.found(words.shuffled()).map { Triple(it.value, it.first.position, it.last.position) }

    @Test
    fun `of the names but some values the same as of those names alone`() {
        val random = Random(4)
        val every = names.values.flatten() + listOf("и", "со", "пошёл", "новый")
        val base = NameMatcher.Base(names, listOf("ru"), stems)
        repeat(500) {
            val without = names.keys.filter { random.nextInt(3) == 0 }.toSet()
            val text = words(random, List(random.nextInt(1, 25)) { every[random.nextInt(every.size)] })
            val alone = NameMatcher(names - without, listOf("ru"), stems)
            assertEquals(found(alone, text), found(base.without(without), text), "without $without")
        }
    }

    @Test
    fun `a word alone is of a value alone, the others left out`() {
        val base = NameMatcher.Base(names, listOf("ru"), stems)
        val text = words(Random(1), listOf("Поттер")).map { NamedWord(it.stem, it.position, true) }
        assertEquals(emptyList(), found(base.without(emptySet()), text))
        assertEquals(listOf(Triple("james", 0, 0)), found(base.without(setOf("harry", "lily")), text))
    }

    @Test
    fun `of two words at one place, the one that begins a name`() {
        val matcher = NameMatcher(names, listOf("ru"), stems)
        val ron = stems.of("Рон Уизли", "ru")
        val text = listOf(NamedWord("иной", 0, true), NamedWord(ron[0], 0, true), NamedWord(ron[1], 1, true))
        assertEquals(listOf(Triple("ron", 0, 1)), found(matcher, text))
        assertEquals(listOf(Triple("ron", 0, 1)), found(matcher, text.reversed()))
    }
}
