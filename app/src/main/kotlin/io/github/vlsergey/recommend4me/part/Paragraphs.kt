package io.github.vlsergey.recommend4me.part

/**
 * THE PARAGRAPHS OF A PART'S TEXT, as the tags are held against them: its lines with a letter in
 * them, stripped — a line of dashes or stars between scenes is none — those in a row shorter than
 * [MIN_CHARS] joined into one, so a line of a dialogue ("Пить.") is not a passage of its own that
 * the name of a tag is close to by its one word; counted from nought in their order. The same
 * cutting where they are encoded and where one is quoted; a paragraph is known by the hash of its
 * text, its part and number telling only where it is now.
 */
object Paragraphs {
    /** The letters a paragraph has at least, its lines joined till it has. */
    const val MIN_CHARS = 200

    /**
     * The cutting in use: the second. The first cut every line apart ([firstCutting]); an answer
     * given on it was on a line, not on a paragraph of this one — the model learns from those of
     * this cutting alone.
     */
    const val CUTTING = 2

    /** The version of the paragraphs' vectors kept: of this cutting, with the hash of every paragraph's text. */
    private const val STORED = 3

    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        lines(text).forEach { line ->
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            if (current.length >= MIN_CHARS) {
                out += current.toString()
                current.clear()
            }
        }
        // The short rest joins the last paragraph, when there is one
        if (current.isNotEmpty()) {
            if (out.isEmpty()) out += current.toString() else out[out.size - 1] = out.last() + "\n" + current
        }
        return out
    }

    /** The paragraphs of the first cutting: every line with a letter apart — what the answers given on it were on. */
    fun firstCutting(text: String): List<String> = lines(text).toList()

    /** The paragraphs of the [cutting] an answer was given on. */
    fun ofCutting(text: String, cutting: Int): List<String> = if (cutting == 1) firstCutting(text) else of(text)

    private fun lines(text: String) = text.lineSequence().map { it.trim() }.filter { line -> line.any(Char::isLetter) }

    /** What the paragraphs' vectors are kept made by: the encoder and the version of what is kept. */
    fun keyOf(encoder: String): String = "$encoder#$STORED"
}
