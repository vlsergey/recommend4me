package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.source.SourceStore
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.BitSet

/**
 * THE VECTORS OF THE TEXTS OF EVERY ITEM OF A SOURCE AS ITS DOCUMENTS HOLD THEM ([ListTexts.packed]), while the
 * documents of a source written whole are made: read in one pass of the source's vectors by the [encoder], off the heap
 * — of the 1.4 million works of ficbook near three gigabytes — and let go as it closes. Of every text [keys] its rows
 * by the items' places ([ids]), of the length of the first vector of it: a vector of another passed over.
 */
internal class EveryVector
private constructor(
    val encoder: String,
    private val keys: List<String>,
    ids: List<String>,
) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val place = HashMap<String, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
    private val count = ids.size
    private val rows = arrayOfNulls<MemorySegment>(keys.size)
    private val width = IntArray(keys.size)
    private val had = Array(keys.size) { BitSet(count) }

    private fun put(id: String, key: Int, packed: ByteArray) {
        val i = place[id] ?: return
        val into =
            rows[key]
                ?: arena.allocate(count.toLong() * packed.size).also {
                    rows[key] = it
                    width[key] = packed.size
                }
        if (packed.size != width[key]) return
        MemorySegment.copy(packed, 0, into, ValueLayout.JAVA_BYTE, i.toLong() * packed.size, packed.size)
        had[key].set(i)
    }

    /** The vectors of the item [id] as its document holds them, by the texts' keys; none of an item of none. */
    fun of(id: String): RowVectors? {
        val i = place[id]?.takeIf { at -> keys.indices.any { had[it][at] } } ?: return null
        return RowVectors(encoder) {
            keys.indices
                .filter { had[it][i] }
                .associate { k ->
                    keys[k] to
                        ByteArray(width[k]).also {
                            MemorySegment.copy(rows[k]!!, ValueLayout.JAVA_BYTE, i.toLong() * width[k], it, 0, width[k])
                        }
                }
        }
    }

    override fun close() = arena.close()

    companion object {
        /** The vectors of the items [ids] of the source [s] by the [encoder], read in one pass of them. */
        fun read(s: SourceStore, encoder: String, ids: List<String>): EveryVector {
            val keys = ListTexts.vectorKeys(s.schema)
            val at = keys.withIndex().associate { it.value to it.index }
            return EveryVector(encoder, keys, ids).also { every ->
                s.textVectors.forEachHalves(encoder, keys) { id, key, halves ->
                    at[key]?.let { every.put(id, it, ListTexts.packedHalves(halves)) }
                }
            }
        }
    }
}
