package io.github.vlsergey.recommend4me.cast

/**
 * THE LINE WHOLE, AS THE MODELS READ IT FROM THE FIRST: the site's line encoded as one text, a vector of it, and every
 * entry of it as the site keeps it a column of the context — "Гарри Поттер/Драко Малфой" one column, whatever names the
 * other works write it with. The halves of the cast ([SaidCast]) are not read.
 */
class WholeLine : CastReader {
    override val id = ID

    override fun read(works: CastWorks): CastRead =
        CastRead(listOfNotNull(works.line), works.entries, List(works.count) { emptyList() })

    companion object {
        const val ID = "cast-line"
    }
}
