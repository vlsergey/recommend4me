package io.github.vlsergey.recommend4me.cast

/**
 * EVERY NAME A FEATURE: each name of the line a column of the context by its form ([NameForm]) — "гарри поттер" one
 * column whichever entry it is in, alone or in a pairing — and each pairing another, its names' forms in their order.
 * The model learns of the works answered what a name the fans write is: "Гарри" of the works where the user confirmed
 * Harry Potter. A name only one work writes teaches nothing and is no column.
 */
class NameFeatures : CastReader {
    override val id = ID

    override fun read(works: CastWorks): CastRead =
        CastRead(emptyList(), works.casts.map(::columns), List(works.count) { emptyList() })

    companion object {
        const val ID = "cast-name-features"

        /** The beginning of the column of a name. */
        const val NAME = "cast.name\u0001"

        /** The beginning of the column of a pairing. */
        const val PAIRING = "cast.pairing\u0001"

        /** The columns of a work's [cast]: of its names, then of its pairings, each once. */
        internal fun columns(cast: SaidCast): List<String> =
            (cast.characters.map { NAME + NameForm.of(it) } + cast.pairings.map { PAIRING + NameForm.ofPairing(it) })
                .distinct()
    }
}
