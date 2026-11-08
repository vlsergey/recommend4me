package io.github.vlsergey.recommend4me.cast

/**
 * EVERY WAY AT ONCE: the line whole ([WholeLine]), the names' vectors ([NameVectors]), the names as features
 * ([NameFeatures]) and the names found in the dictionary ([DictionaryNames]), side by side — the dense, the sparse and
 * the dictionary's table together, as the linking of names to entries joins them; the model weighs each by what it
 * gives.
 */
class EveryWay : CastReader {
    override val id = ID

    override fun encoded(cast: SaidCast): List<String> = vectors.encoded(cast)

    override fun read(works: CastWorks): CastRead =
        CastRead(
            listOfNotNull(works.line) + NameVectors.blocks(works),
            List(works.count) { i -> works.entries[i] + NameFeatures.columns(works.casts[i]) },
            DictionaryNames.named(works),
        )

    private companion object {
        const val ID = "cast-every"

        val vectors = NameVectors()
    }
}
