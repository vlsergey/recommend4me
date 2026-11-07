package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.util.AttributeSource

/**
 * EVERY PARAGRAPH OF A TEXT BEGUN BY A MARK — the word [MARK] at a place of its own before its first word, the text's
 * first paragraph too: in the index, the names named in one paragraph are told by the marks before them
 * ([LuceneSearch.together]). A paragraph ends at a line break, as the texts are written. No word of a text is ever the
 * mark: the tokenizer keeps no '¶'.
 */
internal class ParagraphMarks(input: TokenStream, private val text: String) : TokenFilter(input) {
    private val term = addAttribute(CharTermAttribute::class.java)
    private val offset = addAttribute(OffsetAttribute::class.java)
    private val step = addAttribute(PositionIncrementAttribute::class.java)

    /** The word after a mark, given at the next call. */
    private var held: AttributeSource.State? = null
    private var lastEnd = -1

    override fun incrementToken(): Boolean {
        val word = held
        return when {
            word != null -> {
                given(word)
                true
            }
            !input.incrementToken() -> false
            else -> {
                markedOrPassed()
                true
            }
        }
    }

    /** The word held after a mark given now. */
    private fun given(word: AttributeSource.State) {
        restoreState(word)
        held = null
        step.positionIncrement = 1
        lastEnd = offset.endOffset()
    }

    /**
     * The word just read passed as it is — or, the first of a paragraph, held, and the mark given in its place, at its
     * start.
     */
    private fun markedOrPassed() {
        val start = offset.startOffset()
        val begins = lastEnd < 0 || text.indexOf('\n', lastEnd).let { it in lastEnd until start }
        if (!begins) {
            lastEnd = offset.endOffset()
            return
        }
        held = captureState()
        val increment = step.positionIncrement
        clearAttributes()
        term.setEmpty().append(MARK)
        step.positionIncrement = increment
        offset.setOffset(start, start)
    }

    override fun reset() {
        super.reset()
        held = null
        lastEnd = -1
    }

    companion object {
        /** The word a paragraph begins with in the index. */
        const val MARK = "¶"
    }
}
