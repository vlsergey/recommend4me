package io.github.vlsergey.recommend4me.search

/**
 * A text cut into phrases — its sentences and the items of its lists — so that the one line of a
 * long text nearest to a search by meaning can be shown.
 */
object Phrases {

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val BULLET = Regex("^[-*•·+>\\s]+")
    private val SPACES = Regex("\\s+")
    private val LETTER = Regex("\\p{L}")

    /** At least this many letters and words: shorter pieces are headings and version numbers. */
    private const val MIN_LETTERS = 12
    private const val MIN_WORDS = 3

    /** Longer sentences are cut into pieces of this many words. */
    private const val MAX_WORDS = 40

    fun split(text: String): List<String> = text.lines()
        .flatMap { it.split(SENTENCE_END) }
        .map { it.replace(BULLET, "").replace(SPACES, " ").trim() }
        .flatMap { sentence ->
            val words = sentence.split(' ')
            if (words.size <= MAX_WORDS) listOf(sentence) else words.chunked(MAX_WORDS).map { it.joinToString(" ") }
        }
        .filter { p -> LETTER.findAll(p).count() >= MIN_LETTERS && p.split(' ').size >= MIN_WORDS }
}
