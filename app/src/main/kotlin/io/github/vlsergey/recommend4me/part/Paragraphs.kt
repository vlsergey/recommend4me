package io.github.vlsergey.recommend4me.part

/**
 * THE PARAGRAPHS OF A PART'S TEXT, as the tags are held against them: its lines with a letter in
 * them, stripped — a line of dashes or stars between scenes is none — those in a row shorter than
 * [MIN_CHARS] joined into one, so a line of a dialogue ("Пить.") is not a passage of its own that
 * the name of a tag is close to by its one word; counted from nought in their order. The same
 * cutting where they are encoded and where one is quoted: a paragraph is known by its part and its
 * number.
 */
object Paragraphs {
    /** The letters a paragraph has at least, its lines joined till it has. */
    const val MIN_CHARS = 200

    /** The version of the cutting: vectors made by another are made anew ([keyOf]). */
    private const val VERSION = 2

    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        text.lineSequence().map { it.trim() }.filter { line -> line.any(Char::isLetter) }.forEach { line ->
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

    /** What the paragraphs' vectors are kept made by: the encoder and the version of the cutting. */
    fun keyOf(encoder: String): String = "$encoder#$VERSION"
}
