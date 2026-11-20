package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant

/**
 * Two works of different sites that may be one — [first] the smaller key — with how alike each layer of theirs is (none
 * of a layer either has not): the cosines of the vectors of their titles and of their descriptions, the mean of the
 * smaller over the larger of the numbers of their size ([size]), and of every facet the sites share both have the
 * cosine of their values weighed by rarity ([facets], by the facet) — its [layers]. How likely they are one work, and
 * one series, is the models' of the user's answers ([PairChances]), made when read. [foundFor]: the works it was found
 * for — whose likeliest of the other site it is — [FIRST], [SECOND], both, or neither (an answered pair the search did
 * not keep). [sameAuthor]: the dictionary of authors gives the two one author; [partlySameAuthor]: none in common, but
 * one in part one with the other's ([io.github.vlsergey.recommend4me.author.AuthorKin]).
 */
class DuplicateCandidate(
    val first: ItemKey,
    val second: ItemKey,
    val title: Float,
    val description: Float?,
    val size: Float?,
    val facets: Map<String, Float>,
    var foundFor: Int = 0,
    val sameAuthor: Boolean = false,
    /** How much of the first's title the second's is, and of the second's the first's ([PairLayers.titleWithin]). */
    val titleWithin: Float = 0f,
    val titleHolding: Float = 0f,
    /** How alike the names of the two's authors are letter by letter; none when either has none. */
    val authors: Lexical? = null,
    /** The cosine of the vectors of their titles, and of the trigrams of their other texts ([PairLayers]). */
    val titleVector: Float? = null,
    val letters: Map<LetteredText, Float> = emptyMap(),
    val partlySameAuthor: Boolean = false,
    /**
     * The words of the first work and of the second, as its site states them or worked out of its characters, the
     * search compared them by; none of one not known: what the pairs are sorted by their size by.
     */
    val words: Float? = null,
    val otherWords: Float? = null,
) {
    val pair: Pair<ItemKey, ItemKey>
        get() = first to second

    /** How alike the two are, as a model of pairs reads it. */
    val layers: PairLayers
        get() =
            PairLayers(
                title,
                description,
                size,
                facets,
                sameAuthor,
                first.source,
                second.source,
                titleWithin,
                titleHolding,
                authors,
                titleVector,
                letters,
                partlySameAuthor,
            )

    companion object {
        /**
         * The pair of [first] and [second] of the [layers], found for the works of [foundFor], of the [words] of the
         * first and the [otherWords] of the second.
         */
        fun of(
            first: ItemKey,
            second: ItemKey,
            layers: PairLayers,
            foundFor: Int = 0,
            words: Float? = null,
            otherWords: Float? = null,
        ) =
            DuplicateCandidate(
                first,
                second,
                layers.title,
                layers.description,
                layers.size,
                layers.facets,
                foundFor,
                layers.sameAuthor,
                layers.titleWithin,
                layers.titleHolding,
                layers.authors,
                layers.titleVector,
                layers.letters,
                layers.partlySameAuthor,
                words,
                otherWords,
            )

        /** Of [foundFor], the bit of the first work, and of the second. */
        const val FIRST = 1
        const val SECOND = 2
    }
}

/**
 * When the candidates were last looked for, by which encoder of texts, of how many works; the models of the user's
 * answers as the plugin of pairs [model] packed them ([PairModel]): of one work ([same]) and of one series ([series]),
 * none of a kind no pair was said to be of.
 */
class DuplicateSearch(
    val madeAt: Instant,
    val encoder: String,
    val works: Int,
    val model: String?,
    val same: ByteArray?,
    val series: ByteArray?,
    /** When the models were last fitted, and how long it took; none of models fitted before it was kept. */
    val fittedAt: Instant? = null,
    val fitMillis: Long? = null,
)

/** How alike a pair's texts are letter by letter as kept: of the description, the fandoms, the characters. */
internal fun letters(description: Float?, fandoms: Float?, characters: Float?): Map<LetteredText, Float> =
    listOf(
            LetteredText.DESCRIPTION to description,
            LetteredText.FANDOMS to fandoms,
            LetteredText.CHARACTERS to characters,
        )
        .mapNotNull { (text, v) -> v?.let { text to it } }
        .toMap()

/** How alike the names of a pair's authors are as kept: none of a pair of no names compared. */
internal fun lexical(alike: Float?, within: Float?, holding: Float?): Lexical? = alike?.let {
    Lexical(it, within ?: 0f, holding ?: 0f)
}

/** Numbers by name as kept: "<name>=<number>" a line. */
internal fun written(numbers: Map<String, Number>): String =
    numbers.entries.joinToString("\n") { (name, v) -> "$name=$v" }

/**
 * The numbers by name of a [text] [written]: a line's name before its last '=', a line of none passed over; each name
 * as [one] gives it.
 */
internal fun numbers(text: String, one: (String) -> String): Map<String, Float> {
    val out = LinkedHashMap<String, Float>()
    var from = 0
    while (from <= text.length) {
        val end = text.indexOf('\n', from).takeIf { it >= 0 } ?: text.length
        val eq = text.lastIndexOf('=', end - 1)
        if (eq >= from) out[one(text.substring(from, eq))] = text.substring(eq + 1, end).toFloat()
        from = end + 1
    }
    return out
}
