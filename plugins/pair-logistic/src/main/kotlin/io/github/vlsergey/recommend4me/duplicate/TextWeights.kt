package io.github.vlsergey.recommend4me.duplicate

/**
 * The weights of a model of the layers compared the other way (owner, 2026-11-19: "for every text field both the
 * trigram ones and the vector ones"): the cosine of the titles' vectors and that both have one, an author in part one
 * with the other's, none in common, and of every text compared letter by letter ([LetteredText]) the cosine of their
 * trigrams and that both have it.
 */
internal class TextWeights(
    val titleVector: Double,
    val titled: Double,
    val partlyAuthor: Double,
    val letters: DoubleArray,
    val lettered: DoubleArray,
) {
    /** What the [layers] of a pair add to its score. */
    fun of(layers: OtherLayers): Double {
        var z = 0.0
        if (layers.titled) z += titleVector * layers.titleVector + titled
        if (layers.partlyAuthor) z += partlyAuthor
        for (k in 0 until LETTERED) if (layers.lettered[k]) z += letters[k] * layers.letters[k] + lettered[k]
        return z
    }

    companion object {
        /** Of no weight: a model knowing none of them. */
        val NONE = TextWeights(0.0, 0.0, 0.0, DoubleArray(LETTERED), DoubleArray(LETTERED))
    }
}

/**
 * The layers of a pair compared the other way: the cosine of the titles' vectors ([titleVector], when both have one:
 * [titled]), whether an author of one is in part one with the other's, none in common ([partlyAuthor]), and of every
 * text compared letter by letter the cosine of their trigrams ([letters], when both have it: [lettered]). Filled anew
 * for every pair a search scores: one a search.
 */
internal class OtherLayers {
    var titleVector = 0f
    var titled = false
    var partlyAuthor = false
    val letters = FloatArray(LETTERED)
    val lettered = BooleanArray(LETTERED)

    companion object {
        /** Of none of them: never filled. */
        val NONE = OtherLayers()
    }
}
