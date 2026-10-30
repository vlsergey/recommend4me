package io.github.vlsergey.recommend4me.size

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordsPerCharTest {

    @Test
    fun `words are counted as the sites count them, a hyphen or an apostrophe within a word part of it, a dash alone none`() {
        assertEquals(0, WordCount.of(""))
        assertEquals(3, WordCount.of("Раз, два — три."))
        assertEquals(2, WordCount.of("кто-то don't"))
        assertEquals(2, WordCount.of("конец- начало"))
        assertEquals(2, WordCount.of("- Да -\nнет"))
        assertEquals(2, WordCount.of("Глава 12"))
    }

    /** Works of 20 000 characters: Russian ones of 0.15 words a character, English ones of 0.18, one author's shorter by a tenth; noise of a percent. */
    private fun examples(random: Random = Random(7)): List<SizeExample> = List(300) { i ->
        val english = i % 5 == 0
        val concise = i % 7 == 0
        val ratio = (if (english) 0.18 else 0.15) * (if (concise) 0.9 else 1.0) * exp(0.01 * random.nextGaussian())
        SizeExample(listOfNotNull(if (english) "language:en" else "language:ru", "source:a", if (concise) "author:1" else "author:$i".takeIf { i % 2 == 0 }), 20_000.0 * ratio, 20_000.0)
    }

    @Test
    fun `the language and the author tell the ratio`() {
        val model = WordsPerChar.fit(examples())
        fun ratio(vararg features: String) = exp(model.log(features.toList()))
        assertTrue(abs(ratio("language:ru", "source:a") - 0.15) < 0.002, ratio("language:ru", "source:a").toString())
        assertTrue(abs(ratio("language:en", "source:a") - 0.18) < 0.002, ratio("language:en", "source:a").toString())
        assertTrue(ratio("language:ru", "source:a", "author:1") < 0.141, ratio("language:ru", "source:a", "author:1").toString())
    }

    @Test
    fun `a feature of no work moves nothing, and out of fold the model beats the mean`() {
        val all = examples()
        val model = WordsPerChar.fit(all)
        assertEquals(model.log(listOf("language:ru")), model.log(listOf("language:ru", "tag:unknown")))
        val alone = all.map { SizeExample(emptyList(), it.words, it.chars) }
        assertTrue(WordsPerChar.outOfFold(all) < WordsPerChar.outOfFold(alone) / 3)
    }

    @Test
    fun `a work's own numbers weigh by how many characters they count`() {
        val model = WordsPerChar.fit(examples())
        val features = listOf("language:ru", "source:a")
        val long = model.of(features, SizeExample(features, 2_000_000.0, 10_000_000.0))
        val short = model.of(features, SizeExample(features, 20.0, 100.0))
        assertTrue(abs(long - 0.2) < 0.001, long.toString())
        assertTrue(abs(short - model.of(features, null)) < 0.002, short.toString())
    }
}
