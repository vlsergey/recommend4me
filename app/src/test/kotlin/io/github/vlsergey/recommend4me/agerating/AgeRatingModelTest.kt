package io.github.vlsergey.recommend4me.agerating

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgeRatingModelTest {

    /**
     * Works of one site and three tags: "mild" of mild works, "strong" of strong ones, "any" of any —
     * their ratings stated, or, of some, only bounded as a site's "18+" bounds it (NC-17 to NC-21).
     */
    private fun examples(random: Random = Random(5)): List<RatingExample> = List(600) { i ->
        val strong = random.nextBoolean()
        val features = listOf("source:a", if (strong) "tag:strong" else "tag:mild", "tag:any")
        val rating = if (strong) 3 + random.nextInt(2) else random.nextInt(2)
        // A third of the strong works told only by their bound
        if (strong && i % 3 == 0) RatingExample(features, 3, 4) else RatingExample(features, rating, rating)
    }

    @Test
    fun `a tag of strong works rates a work up, one of mild works down, a bound teaching as a rating does`() {
        val model = assertNotNull(AgeRatingModel.fit(examples(), 5))
        assertEquals(4, model.thresholds.size)
        assertTrue((1 until 4).all { model.thresholds[it] > model.thresholds[it - 1] }, model.thresholds.toList().toString())
        assertTrue(model.weights.getValue("tag:strong") > model.weights.getValue("tag:mild"), model.weights.toString())
        val mild = model.chances(listOf("source:a", "tag:mild", "tag:any"))
        val strong = model.chances(listOf("source:a", "tag:strong", "tag:any"))
        assertEquals(1.0, mild.sum(), 1e-9)
        assertTrue(mild.indices.maxBy { mild[it] } <= 1, mild.toList().toString())
        assertTrue(strong.indices.maxBy { strong[it] } >= 3, strong.toList().toString())
        // Fitted, its loss below that of telling every rating alike
        assertTrue(AgeRatingModel.loss(model, examples()) < kotlin.math.ln(5.0), "loss ${AgeRatingModel.loss(model, examples())}")
    }

    @Test
    fun `a site all of whose works are strong is strong by itself, not by the tags it shares with a mild one`() {
        val random = Random(9)
        // Books of "magic" mild and stated; games of "magic" too, every one bounded R to NC-21, many more of them
        val books = List(300) { RatingExample(listOf("source:books", "tag:magic"), random.nextInt(2), random.nextInt(2).let { it }) }
            .map { RatingExample(it.features, it.low, it.low) }
        val games = List(3000) { RatingExample(listOf("source:games", "tag:magic"), 2, 4) }
        val model = assertNotNull(AgeRatingModel.fit(books + games, 5))
        val book = model.chances(listOf("source:books", "tag:magic"))
        assertTrue(book.indices.maxBy { book[it] } <= 1, "a book of magic: ${book.toList()}, weights ${model.weights}")
        assertTrue(model.weights.getValue("source:games") > model.weights.getValue("source:books"), model.weights.toString())
    }

    @Test
    fun `works that may have any rating teach nothing`() {
        assertNull(AgeRatingModel.fit(listOf(RatingExample(listOf("tag:1"), 0, 4)), 5))
    }
}
