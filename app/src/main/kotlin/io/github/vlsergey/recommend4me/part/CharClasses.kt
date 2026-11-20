package io.github.vlsergey.recommend4me.part

/**
 * THE CHARACTERS OF A TEXT BY THEIR CLASSES ([NAMES]): letters and digits of one byte in UTF-8, of two, of three or
 * more; punctuation of one byte, of more; spaces; line breaks; the rest — so a site's count of characters is told as a
 * sum of them, each class by a coefficient of the site's own (owner, 2026-11-20: "sites count the characters
 * differently"; "the letters of two bytes in UTF-8 apart"): a site counting bytes counts a Cyrillic letter twice, a
 * site counting with no spaces none of them. Counted of the text's bytes as kept, every code point once.
 */
object CharClasses {
    /** The classes, in the order of a count's numbers. */
    val NAMES = listOf("letters1", "letters2", "letters3", "punctuation1", "punctuation2", "spaces", "breaks", "other")

    const val LETTERS_ONE = 0
    const val LETTERS_TWO = 1
    const val LETTERS_MORE = 2
    const val PUNCTUATION_ONE = 3
    const val PUNCTUATION_MORE = 4
    const val SPACES = 5
    const val BREAKS = 6
    const val OTHER = 7

    /** The count of every class of the text [text]. */
    fun of(text: String): IntArray = of(text.toByteArray(Charsets.UTF_8))

    /** The count of every class of the text of the UTF-8 [bytes]. */
    fun of(bytes: ByteArray): IntArray {
        val out = IntArray(NAMES.size)
        var i = 0
        while (i < bytes.size) {
            val lead = bytes[i].toInt() and BYTE
            val length =
                when {
                    lead < 0x80 -> 1
                    lead < 0xE0 -> 2
                    lead < 0xF0 -> THREE
                    else -> FOUR
                }
            var point = if (length == 1) lead else lead and (LEAD_BITS shr length)
            for (k in 1 until length) point =
                (point shl CONTINUATION) or ((bytes.getOrElse(i + k) { 0 }.toInt()) and LOW)
            out[classOf(point, length)]++
            i += length
        }
        return out
    }

    private fun classOf(point: Int, bytes: Int): Int =
        when {
            point == '\n'.code || point == '\r'.code -> BREAKS
            Character.isLetterOrDigit(point) ->
                when (bytes) {
                    1 -> LETTERS_ONE
                    2 -> LETTERS_TWO
                    else -> LETTERS_MORE
                }
            Character.isWhitespace(point) || Character.isSpaceChar(point) -> SPACES
            punctuation(point) -> if (bytes == 1) PUNCTUATION_ONE else PUNCTUATION_MORE
            else -> OTHER
        }

    private fun punctuation(point: Int): Boolean =
        when (Character.getType(point).toByte()) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }

    private const val BYTE = 0xFF
    private const val LOW = 0x3F
    private const val CONTINUATION = 6
    private const val THREE = 3
    private const val FOUR = 4

    /** The bits of a lead byte that are its code point's, by the sequence's length shifted. */
    private const val LEAD_BITS = 0x7F
}
