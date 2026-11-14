package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import java.nio.ByteBuffer
import org.springframework.stereotype.Component

/** A site's fandom as rows of [FandomRows.rows]: its name's, and its description's — [NONE] of a fandom of none. */
class FandomRowsOf(val name: Int, val description: Int) {
    companion object {
        const val NONE = -1
    }
}

/**
 * THE VECTORS OF THE SITES' FANDOMS' NAMES AND DESCRIPTIONS AS ROWS ([rows]), read as they are kept — half precision,
 * two bytes a number, copied as they are into one block of memory off the heap ([HalfMatrix]), never made single
 * precision on the way: what the learning of their likeness sends to the card at once. A vector kept of two texts — one
 * name of two fandoms — is one row. Of every site's fandom, its rows ([ofFandom]); none of a fandom whose name is not
 * encoded yet.
 */
class FandomRows(val rows: HalfMatrix, val ofFandom: Map<SiteFandomRef, FandomRowsOf>) {
    /** The rows of the sites' fandoms [refs], their names' and their descriptions', each once. */
    fun of(refs: Collection<SiteFandomRef>): IntArray =
        refs
            .flatMap { ref -> ofFandom[ref]?.let { listOf(it.name, it.description) }.orEmpty() }
            .filter { it != FandomRowsOf.NONE }
            .distinct()
            .toIntArray()
}

/** Reads the [FandomRows] of every source of universes. */
@Component
class FandomRowsReading(private val stores: Stores, private val phrases: PhraseVectors) {

    fun read(): FandomRows {
        val read =
            sources().map { (s, facet) ->
                val names = s.items.facetNames(facet).mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
                val said =
                    s.items
                        .facetDescriptions(facet, names.keys)
                        .mapValues { it.value.trim() }
                        .filterValues {
                            it.isNotEmpty()
                        }
                Triple(s, names to said, phrases.cachedHalves(s, names.values + said.values))
            }
        // The encoder's length: of a vector of another — an encoder changed, the old ones still kept — no row
        val dim =
            read.flatMap { it.third.values }.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key?.div(2)
                ?: 0
        val distinct = LinkedHashMap<ByteBuffer, Int>()
        fun rowOf(bytes: ByteArray?): Int =
            bytes?.takeIf { it.size == dim * 2 }?.let { distinct.getOrPut(ByteBuffer.wrap(it)) { distinct.size } }
                ?: FandomRowsOf.NONE
        val ofFandom = HashMap<SiteFandomRef, FandomRowsOf>()
        read.forEach { (s, texts, kept) ->
            val (names, said) = texts
            names.forEach { (key, name) ->
                val row = rowOf(kept[name]).takeIf { it != FandomRowsOf.NONE } ?: return@forEach
                ofFandom[SiteFandomRef(s.id, key)] = FandomRowsOf(row, rowOf(said[key]?.let(kept::get)))
            }
        }
        val rows = HalfMatrix(distinct.size, dim)
        distinct.forEach { (bytes, row) -> rows.putHalves(row, bytes.array()) }
        return FandomRows(rows, ofFandom)
    }

    private fun sources(): List<Pair<SourceStore, String>> =
        stores
            .withUniverses()
            .flatMap { it.sources }
            .mapNotNull { s ->
                UniverseFacet.fandomFacet(s.schema)?.let { s to it }
            }
}
