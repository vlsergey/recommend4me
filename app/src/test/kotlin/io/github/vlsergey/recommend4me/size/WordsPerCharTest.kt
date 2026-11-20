package io.github.vlsergey.recommend4me.size

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordsPerCharTest {

    /** The features of the examples, by their numbers. */
    private val features = SizeFeatures()

    private fun ids(vararg names: String) = names.map(features::id).toIntArray()

    /**
     * Works of 1 000 to 100 000 characters: Russian ones of 0.15 words a character, English ones of 0.18; of a hundred
     * authors, each of a step of their own of two percents or so, one of them shorter by a tenth; a work's own
     * deviation of a percent, and its count's the more the fewer its characters — a variance of 10 over them, as much
     * as the work's own at 100 000 characters.
     */
    private fun examples(random: Random = Random(7)): List<SizeExample> {
        val steps = DoubleArray(AUTHORS) { if (it == 1) ln(0.9) else AUTHOR_STEP * random.nextGaussian() }
        return List(COUNT) { i ->
            val english = i % 5 == 0
            val author = i % AUTHORS
            val chars = Math.pow(10.0, 3 + 2 * random.nextDouble())
            val deviation = sqrt(OWN + COUNTING / chars) * random.nextGaussian()
            val ratio = (if (english) 0.18 else 0.15) * exp(steps[author] + deviation)
            SizeExample(
                ids(if (english) "languages:en" else "languages:ru", "source:a", "author:$author"),
                chars * ratio,
                chars,
            )
        }
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
        val ofModel = WordsPerChar.outOfFold(all, features.names)
        val ofMean = WordsPerChar.outOfFold(alone, features.names)
        assertTrue(ofModel < ofMean, "$ofModel of the model, $ofMean of the mean")
    }

    @Test
    fun `a work's own numbers weigh by how many characters they count`() {
        val model = WordsPerChar.fit(examples(), features.names)
        val own = ids("languages:ru", "source:a")
        val long = model.of(own, SizeExample(own, 2_000_000.0, 10_000_000.0))
        val short = model.of(own, SizeExample(own, 20.0, 100.0))
        assertTrue(abs(long - 0.2) < 0.001, long.toString())
        assertTrue(abs(short - model.of(own, null)) < 0.002, short.toString())
        // Worked out of the data: as many characters as make the count as uncertain as the work's own deviation
        assertTrue(model.sample in 50_000.0..200_000.0, model.sample.toString())
    }

    private companion object {
        const val COUNT = 2000
        const val AUTHORS = 100
        const val AUTHOR_STEP = 0.02

        /** The variance of a work's own deviation, and of its count by a character. */
        const val OWN = 1e-4
        const val COUNTING = 10.0
    }
}
