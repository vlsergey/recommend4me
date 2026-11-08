package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.suggestion.InputBlock
import kotlin.math.sqrt

/**
 * EVERY NAME A VECTOR OF ITS OWN, THE WORK'S MEAN OF THEM: each name of the line encoded apart, as a text, and the mean
 * of a work's names one block of its input ([CHARACTERS]); each pairing encoded as its names written "A / B" in the
 * order of their forms, the mean of the work's pairings another ([PAIRINGS]) — of unit length, both. The pooling of a
 * set by its mean (Deep Sets): what the line names, whatever its order and its number, as the dictionary's names of the
 * values are read too — a name of a character read as a work's every text
 * ([NameHint][io.github.vlsergey.recommend4me.suggestion.NameHint]), these blocks among them. A work whose names are
 * not encoded yet has no row of the block.
 */
class NameVectors : CastReader {
    override val id = ID

    override fun encoded(cast: SaidCast): List<String> = cast.characters + cast.pairings.map(::written)

    override fun read(works: CastWorks): CastRead =
        CastRead(blocks(works), List(works.count) { emptyList() }, List(works.count) { emptyList() })

    companion object {
        const val ID = "cast-name-vectors"

        /** The block of the mean of a work's names. */
        const val CHARACTERS = "text:cast.characters"

        /** The block of the mean of a work's pairings. */
        const val PAIRINGS = "text:cast.pairings"

        /** A pairing as one text: its names in the order of their forms, apart by " / ". */
        fun written(names: List<String>): String = names.sortedBy(NameForm::of).joinToString(" / ")

        /** The blocks of the names and of the pairings, each of the works that have a vector of it. */
        internal fun blocks(works: CastWorks): List<InputBlock> =
            listOfNotNull(
                mean(CHARACTERS, works) { it.characters },
                mean(PAIRINGS, works) { cast -> cast.pairings.map(::written) },
            )

        /**
         * The block [name] of the mean of every work's texts [of] its cast, of unit length; null when no work has one
         * encoded.
         */
        private fun mean(name: String, works: CastWorks, of: (SaidCast) -> List<String>): InputBlock? {
            val found = works.casts.map { cast -> of(cast).mapNotNull { works.vectors[it] } }
            val dim = found.firstNotNullOfOrNull { it.firstOrNull() }?.size ?: return null
            val present = BooleanArray(works.count) { found[it].isNotEmpty() }
            val m = Matrix(works.count, dim)
            found.forEachIndexed { i, vectors ->
                if (vectors.isEmpty()) return@forEachIndexed
                val sum = FloatArray(dim)
                vectors.forEach { v -> for (c in 0 until dim) sum[c] += v[c] }
                val length = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
                for (c in 0 until dim) m.held[m.row(i) + c] = sum[c] / length
            }
            return InputBlock.of(name, m, present)
        }
    }
}
