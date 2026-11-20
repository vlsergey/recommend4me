package io.github.vlsergey.recommend4me.part

/**
 * How a text's words are counted as the sites count them: letters and digits, a hyphen or an apostrophe within them
 * part of the word — ficbook's count to a tenth of a percent, and a dash standing alone no word.
 */
object WordCount {
    fun of(text: String): Int {
        var words = 0
        var inWord = false
        for (i in text.indices) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                if (!inWord) words++
                inWord = true
            } else if (!inWord || !joins(text, i)) inWord = false
        }
        return words
    }

    /**
     * Whether the character at [i], within a word, joins it to the letter or digit after it: a hyphen, an apostrophe.
     */
    private fun joins(text: String, i: Int): Boolean =
        text[i] in WITHIN && i + 1 < text.length && text[i + 1].isLetterOrDigit()

    private const val WITHIN = "-'’‐‑"
}
