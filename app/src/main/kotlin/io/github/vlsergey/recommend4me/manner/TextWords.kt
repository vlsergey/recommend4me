package io.github.vlsergey.recommend4me.manner

/**
 * THE WORDS OF A TEXT AS ITS MANNER COUNTS THEM: a run of letters, a hyphen or an apostrophe between two letters part
 * of it — "что-то" one word, "don't" one — lowercased, with whether it was written with a capital. Nothing but the
 * letters: no forms of a word told apart from another, no grammar.
 */
internal object TextWords {
    /** Every word of the [text], in its order. */
    fun forEach(text: String, action: (word: String, capital: Boolean) -> Unit) {
        var start = -1
        for (i in 0..text.length) {
            val c = if (i < text.length) text[i] else ' '
            when {
                c.isLetter() -> if (start < 0) start = i
                start < 0 -> Unit
                c in WITHIN && i + 1 < text.length && text[i + 1].isLetter() -> Unit
                else -> {
                    action(text.substring(start, i).lowercase(), text[start].isUpperCase())
                    start = -1
                }
            }
        }
    }

    /** What may stand within a word, between two letters. */
    private const val WITHIN = "-'’‐‑"
}
