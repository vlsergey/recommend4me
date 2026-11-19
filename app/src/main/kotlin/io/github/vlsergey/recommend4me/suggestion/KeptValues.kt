package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE VALUES A TASK OF THE MODEL KEPT IS SCORED AMONG ([FacetChain.scored]): the model's own ([keys] first, in its
 * order) and after them those the works may have that the model was not made of (owner, 2026-11-19: of the pairings,
 * the pairs of a work's own characters, which no dictionary lists; a value come to the dictionary since the fitting) —
 * of these their names' vectors ([names], a row a value, the model's own rows nought) and their groups ([groupings]):
 * what a suggester scores a value it was not made of by ([io.github.vlsergey.recommend4me.suggestion.NewValues]). Of no
 * new value no names, no groups: the model knows every value.
 */
internal class KeptValues(val keys: List<String>, val names: Matrix?, val groupings: List<ValueGrouping>) {
    companion object {
        /**
         * The model's values [own] and those beyond them of the works of [read] may have ([Vocabulary.allowed] of the
         * [vocabulary]), the names and groups of these read now — before the task is made with the database refused.
         */
        fun of(
            store: SourceStore,
            facet: FacetDef,
            own: List<String>,
            read: WorksRead,
            vocabulary: Vocabulary?,
            vocabularies: FacetVocabularies,
            encoder: TextEncoder,
        ): KeptValues {
            val known = own.toHashSet()
            val beyond =
                vocabulary?.allowed?.let { allowed ->
                    read.ids.flatMapTo(LinkedHashSet()) { allowed(it).orEmpty() }.filter { it !in known }
                }
            return if (beyond.isNullOrEmpty()) KeptValues(own, null, emptyList())
            else withNew(store, facet, own, beyond, read, vocabularies, encoder)
        }

        /** The model's values [own] and the [beyond] after them, the names and groups of these read now. */
        private fun withNew(
            store: SourceStore,
            facet: FacetDef,
            own: List<String>,
            beyond: List<String>,
            read: WorksRead,
            vocabularies: FacetVocabularies,
            encoder: TextEncoder,
        ): KeptValues {
            val keys = own + beyond
            // The dictionary's word on the new values: the texts of their names and their groups
            val side = vocabularies.sideOf(store, facet, read.characters, beyond.toSet())
            val corrections = read.corrections.values.flatten()
            val made =
                ValueNames.matrix(
                    store,
                    facet.key,
                    beyond,
                    ValueIndex(beyond),
                    corrections,
                    side?.values.orEmpty(),
                    encoder,
                    asKept = true,
                )
            val names = Matrix(keys.size, encoder.dim)
            beyond.indices.forEach { names.held.copy(made.held, made.row(it), names.row(own.size + it), encoder.dim) }
            return KeptValues(keys, names, side?.groupings?.invoke(keys).orEmpty())
        }
    }
}
