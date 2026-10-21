package io.github.vlsergey.recommend4me.part

/**
 * THE PARAGRAPHS OF A PART'S TEXT: its lines with a letter in them, stripped, counted from nought in
 * their order — a line of dashes or stars between scenes is none. The same cutting where they are
 * encoded and where one is quoted: a paragraph is known by its part and its number.
 */
object Paragraphs {
    fun of(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { line -> line.any(Char::isLetter) }.toList()
}
