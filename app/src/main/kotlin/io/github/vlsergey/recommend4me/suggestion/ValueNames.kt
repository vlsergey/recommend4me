package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors

/**
 * THE NAMES OF A FACET'S VALUES AS ITS MODEL READS THEM: the vector of every value's name as a query ([matrix]), and
 * every name a value goes by as a text naming it ([texts]) — kept with the source's vectors of names, encoded again
 * only when new.
 */
internal object ValueNames {
    /** Up to this many values their names' vectors are read by their keys; more, the facet's whole at once. */
    private const val FEW_NAMES = 3000

    /** Names encoded in one call: memory, not meaning. */
    private const val ENCODE_BATCH = 64

    /**
     * The vectors of the [values]' names as the rows of a matrix, in their order ([index]): of [known]'s texts for
     * those it has, of the user's [corrections] naming them, else of the source's names ([nameVectors]).
     */
    fun matrix(
        store: SourceStore,
        facet: String,
        values: List<String>,
        index: ValueIndex,
        corrections: List<FacetCorrection>,
        known: Map<String, String>,
        encoder: TextEncoder,
        asKept: Boolean,
    ): Matrix {
        val d = encoder.dim
        val names = Matrix(values.size, d)
        nameVectors(store, facet, values, corrections, known, encoder, asKept).forEach { (key, v) ->
            index[key]?.let { names.held.put(v, 0, names.row(it), d) }
        }
        return names
    }

    /**
     * The vectors of the values' names as queries — of [known]'s texts for those it has — encoded now where missing or
     * the name changed. [asKept]: of works read by the model kept, the vector kept of a value is its vector whatever
     * its name now — the one the model was fitted with; a renamed value is encoded again at the next fitting, not at
     * every reading.
     */
    private fun nameVectors(
        store: SourceStore,
        facet: String,
        values: List<String>,
        corrections: List<FacetCorrection>,
        known: Map<String, String>,
        encoder: TextEncoder,
        asKept: Boolean = false,
    ): Map<String, FloatArray> {
        // The names [known] gives are the dictionary's: the source asked of the others alone
        val names =
            store.names(facet, values.filter { it !in known }) +
                corrections.filter { it.facet == facet && it.name != null }.associate { it.key to it.name!! } +
                known
        // Of a few values — one item's — those alone read; of every value, the facet's whole
        val stored = store.valueNames.of(facet, encoder.id, values.takeIf { it.size <= FEW_NAMES })
        val out = HashMap<String, FloatArray>()
        val todo = ArrayList<Pair<String, String>>()
        values.forEach { key ->
            val name = names[key] ?: key
            val have = stored[key]
            if (have != null && (asKept || have.first == Vectors.hash(name))) out[key] = have.second
            else todo += key to name
        }
        todo.chunked(ENCODE_BATCH).forEach { batch ->
            val vectors = encoder.encode(batch.map { it.second }, TextKind.QUERY)
            val rows =
                batch.indices.mapNotNull { k ->
                    vectors[k]?.let { Triple(batch[k].first, Vectors.hash(batch[k].second), it) }
                }
            store.valueNames.save(facet, encoder.id, rows)
            rows.forEach { (key, _, v) -> out[key] = v }
        }
        return out
    }

    /**
     * Every name of every value as a text of a work is encoded — a passage, not a query — each with the value it names;
     * kept with the source's vectors of names under "hint:<facet>", encoded again only when new. The value of every
     * name, and the names' vectors as the rows of one matrix, read into it as they are streamed: thousands of names of
     * a thousand numbers each are not held twice. A name the encoder gave no vector is left out.
     */
    fun texts(
        store: SourceStore,
        facet: String,
        names: Map<String, List<String>>,
        encoder: TextEncoder,
    ): Pair<List<String>, Matrix> {
        if (names.isEmpty()) return emptyList<String>() to Matrix(0, 0)
        val kept = "hint:$facet"
        val wanted = names.flatMap { (value, list) -> list.distinct().map { value to it } }
        val at = wanted.withIndex().associate { (k, w) -> "${w.first}\u0001${w.second}" to k }
        val hashes = wanted.map { Vectors.hash(it.second) }
        val texts = Matrix(wanted.size, encoder.dim)
        val have = BooleanArray(wanted.size)
        fun put(k: Int, v: FloatArray) {
            if (v.size != texts.cols) return
            texts.held.put(v, 0, texts.row(k), texts.cols)
            have[k] = true
        }
        store.valueNames.forEach(kept, encoder.id) { key, hash, v ->
            at[key]?.takeIf { hashes[it] == hash }?.let { put(it, v) }
        }
        wanted.indices
            .filter { !have[it] }
            .chunked(ENCODE_BATCH)
            .forEach { batch ->
                val vectors = encoder.encode(batch.map { wanted[it].second }, TextKind.PASSAGE)
                val rows =
                    batch.indices.mapNotNull { j ->
                        vectors[j]?.let {
                            Triple(
                                "${wanted[batch[j]].first}\u0001${wanted[batch[j]].second}",
                                hashes[batch[j]],
                                it,
                            )
                        }
                    }
                store.valueNames.save(kept, encoder.id, rows)
                batch.indices.forEach { j -> vectors[j]?.let { put(batch[j], it) } }
            }
        val rows = wanted.indices.filter { have[it] }
        return rows.map { wanted[it].first } to if (rows.size == wanted.size) texts else texts.gather(rows.toIntArray())
    }
}
