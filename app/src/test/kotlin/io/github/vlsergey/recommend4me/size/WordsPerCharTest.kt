package io.github.vlsergey.recommend4me.size

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordsPerCharTest {

    /** The features of the examples, by their numbers. */
    private val features = SizeFeatures()

    private fun ids(vararg names: String) = names.map(features::id).toIntArray()

    /**
     * Works of 20 000 characters: Russian ones of 0.15 words a character, English ones of 0.18, one author's shorter by
     * a tenth; noise of a percent.
     */
    private fun examples(random: Random = Random(7)): List<SizeExample> =
        List(300) { i ->
            val english = i % 5 == 0
            val concise = i % 7 == 0
            val ratio = (if (english) 0.18 else 0.15) * (if (concise) 0.9 else 1.0) * exp(0.01 * random.nextGaussian())
            SizeExample(
                ids(
                    *listOfNotNull(
                            if (english) "languages:en" else "languages:ru",
                            "source:a",
                            if (concise) "author:1" else "author:$i".takeIf { i % 2 == 0 },
                        )
                        .toTypedArray()
                ),
                20_000.0 * ratio,
                20_000.0,
            )
        }

    @Test
    fun `the language and the author tell the ratio`() {
        val model = WordsPerChar.fit(examples(), features.names)
        fun ratio(vararg features: String) = exp(model.log(ids(*features)))
        assertTrue(abs(ratio("languages:ru", "source:a") - 0.15) < 0.002, ratio("languages:ru", "source:a").toString())
        assertTrue(abs(ratio("languages:en", "source:a") - 0.18) < 0.002, ratio("languages:en", "source:a").toString())
        assertTrue(
            ratio("languages:ru", "source:a", "author:1") < 0.141,
            ratio("languages:ru", "source:a", "author:1").toString(),
        )
    }

    @Test
    fun `a feature of no work moves nothing, and out of fold the model beats the mean`() {
        val all = examples()
        val model = WordsPerChar.fit(all, features.names)
        assertEquals(model.log(ids("languages:ru")), model.log(ids("languages:ru", "tag:unknown")))
        val alone = all.map { SizeExample(IntArray(0), it.words, it.chars) }
        assertTrue(WordsPerChar.outOfFold(all, features.names) < WordsPerChar.outOfFold(alone, features.names) / 3)
    }

    @Test
    fun `a work's own numbers weigh by how many characters they count`() {
        val model = WordsPerChar.fit(examples(), features.names)
        val own = ids("languages:ru", "source:a")
        val long = model.of(own, SizeExample(own, 2_000_000.0, 10_000_000.0))
        val short = model.of(own, SizeExample(own, 20.0, 100.0))
        assertTrue(abs(long - 0.2) < 0.001, long.toString())
        assertTrue(abs(short - model.of(own, null)) < 0.002, short.toString())
    }
}
