package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import kotlin.test.Test
import kotlin.test.assertEquals

class IrregularVerbsTest {

    private val analyzer = object : Analyzer() {
        override fun createComponents(fieldName: String): TokenStreamComponents {
            val source = StandardTokenizer()
            return TokenStreamComponents(source, IrregularVerbs.filter(PorterStemFilter(LowerCaseFilter(source))))
        }
    }

    private fun tokens(text: String): List<String> = analyzer.tokenStream("", text).use { stream ->
        val term = stream.addAttribute(CharTermAttribute::class.java)
        stream.reset()
        val out = ArrayList<String>()
        while (stream.incrementToken()) out += term.toString()
        stream.end()
        out
    }

    @Test
    fun `every form of an irregular verb is its base, as the stemmer leaves the regular ones`() {
        assertEquals(List(5) { "steal" }, tokens("steal steals stealing stole stolen"))
        assertEquals(List(4) { "take" }, tokens("take took taken taking"))
        assertEquals(listOf("catch", "catch"), tokens("caught catches"))
    }

    @Test
    fun `forms that are as often other words stay what they are`() {
        assertEquals(listOf("left", "rose", "ground"), tokens("left rose ground"))
    }
}
