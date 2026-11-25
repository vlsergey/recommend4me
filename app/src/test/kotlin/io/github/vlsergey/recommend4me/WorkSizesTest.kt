package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.DuplicateAnswer
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.SizeNumber
import io.github.vlsergey.recommend4me.duplicate.DuplicatesController
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.size.WordsPerCharLearning
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The sizes of the works: a number the site states shown as it is, the other worked out by the words to a character the
 * texts teach — of a content type and two sites of their own ([TYPE], [A], [B]): the model of the type learns of their
 * one example alone, of no other class's chapters (one of them taught it 1 234 words of 6 000 characters in the CI's
 * order of the classes, 2026-11-25).
 */
class WorkSizesTest : SiteTestBase() {
    companion object {
        const val TYPE = "sizes"
        const val A = "sizesa"
        const val B = "sizesb"
    }

    @Autowired lateinit var duplicates: DuplicatesController

    @Autowired lateinit var workSizes: WorkSizes

    @Autowired lateinit var sizeLearning: WordsPerCharLearning

    private fun captureOn(site: String, body: String) =
        captures.capturePage(CaptureRequest("https://$site.example/work/${body.substringBefore('|')}", body))

    @Test
    fun `a number the site states shown as it is, the other worked out by the words per character the texts teach`() {
        captureOn(A, "z1|The Long Road|river|A road along a river||")
        captureOn(B, "z2|The Short Road|river|A road along a brook||")
        val type = stores.type(TYPE)!!
        val a = stores.source(A)!!
        val b = stores.source(B)!!
        a.items.setNumbers("z1", mapOf("words" to 900.0))
        b.items.setNumbers("z2", mapOf("chars" to 6000.0))
        // Words of five letters and a space: one word to six characters
        a.parts.save("z1", listOf(PartData("1", 0, null, "слово ".repeat(1000))))
        // Written past the application's events: their documents of the index of the list, which the model reads, made
        listIndexing.itemsChanged(a, listOf("z1"), WorkDatum.DATA, "items")
        listIndexing.itemsChanged(b, listOf("z2"), WorkDatum.DATA, "items")
        background.run()
        sizeLearning.fit(type)
        assertEquals(1000, a.parts.counts.counted().getValue("z1").first.toInt())
        val first = workSizes.ofItems(type, a, listOf("z1"), a.items.numbersOf(listOf("z1")), emptyMap()).getValue("z1")
        assertEquals(SizeNumber(900.0, true), first.words)
        assertEquals(false, first.chars!!.stated)
        assertTrue(first.chars!!.value in 5300.0..5500.0, first.chars.toString())
        val second =
            workSizes.ofItems(type, b, listOf("z2"), b.items.numbersOf(listOf("z2")), emptyMap()).getValue("z2")
        assertEquals(SizeNumber(6000.0, true), second.chars)
        assertEquals(false, second.words!!.stated)
        // Of the one example the model has, its ratio
        assertTrue(second.words!!.value in 950.0..1050.0, second.words.toString())
        // The search of duplicates writes the words it compared with the pair — an answered pair is kept by it —:
        // what the pairs are sorted by their size by
        val z1 = ItemKey(A, "z1")
        val z2 = ItemKey(B, "z2")
        duplicates.answerDuplicate(
            TYPE,
            DuplicateAnswer(ItemRef(A, "z1"), ItemRef(B, "z2"), DuplicateAnswer.Verdict.DIFFERENT),
        )
        background.run()
        duplicates.searchDuplicates(TYPE)
        background.run()
        val pair = type.links.ordered(z1, z2)
        val kept = type.duplicates.reads.ofPairs(listOf(pair)).single()
        val words = if (pair.first == z1) kept.words to kept.otherWords else kept.otherWords to kept.words
        assertEquals(900f, words.first)
        assertTrue(words.second!! in 950f..1050f, words.toString())
        // A text changed is counted anew as it is written, the item's sum with it
        a.parts.save("z1", listOf(PartData("1", 0, null, "слово")))
        assertEquals(1.0 to 5.0, a.parts.counts.counted()["z1"])
    }
}
