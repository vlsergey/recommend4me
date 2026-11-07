package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * How one input block's vectors become features: scaled by √dim over the typical length of the
 * vectors — a coordinate about as large as a one-hot feature — and NOT CENTRED. An item is judged by
 * how alike it is to the items learnt from, not by how it departs from their mean: centred, an item
 * unlike every one of them comes out their opposite (a work of dragons the opposite of the works of
 * space), and the weights learnt of them say the opposite of what they say of those — with no data
 * to stop it. Uncentred, it is near nought along what was learnt, and judged by the base rates; the
 * part all texts share is learnt as a bias. By the vectors' own length, not by how far they
 * spread: a block nearly the same for every item would be stretched without end. An item without
 * the block: no vector, and its mark of having the block off. (A centre of nought is kept in the
 * layout's bytes, as a kept model wrote it; one of any other is a layout no longer read.)
 */
class BlockScale(val name: String, val dim: Int, val scale: Float) {
    companion object {
        /** Of the rows [rows] of [vectors] — every row when null; null of none, or of vectors of no length. */
        fun of(name: String, vectors: Matrix, rows: IntArray? = null): BlockScale? =
            of(name, rows?.size ?: vectors.rows, vectors.cols) { k, into -> vectors.held.take(into, 0, vectors.row(rows?.get(k) ?: k), vectors.cols) }

        /** Of every row of an input block's [vectors]. */
        fun of(name: String, vectors: HalfMatrix): BlockScale? = of(name, vectors.rows, vectors.cols) { k, into -> vectors.take(k, into) }

        /** Of [count] vectors of [d] numbers, each taken by [take]: their typical length. */
        private fun of(name: String, count: Int, d: Int, take: (Int, FloatArray) -> Unit): BlockScale? {
            if (count == 0 || d == 0) return null
            val v = FloatArray(d)
            var length = 0.0
            for (k in 0 until count) {
                take(k, v)
                var s = 0.0
                for (q in 0 until d) s += v[q] * v[q]
                length += sqrt(s)
            }
            length /= count
            return BlockScale(name, d, if (length > 0) (sqrt(d.toDouble()) / length).toFloat() else 1f)
        }
    }
}

/**
 * THE FEATURES OF THE ITEMS AND OF THE VALUES, laid out as on the task the model was fitted on, so
 * that a later task of the same facet — new items, new values, a block an item gained — is read
 * the same way.
 *
 * An item: a constant; for every input block, whether the item has it and its centred and scaled
 * vector; a one-hot of every value of its other facets the fitted task knew.
 *
 * A value: a constant; whether its name is encoded and the name's centred and scaled vector; a
 * one in every group of it in every grouping — a pairing has two, one for each of its characters.
 * What is learnt of a value beyond its name — its own vector and bias — is kept by its key
 * ([valueKeys]).
 */
class Design(
    val blocks: List<BlockScale>,
    val contextKeys: List<String>,
    val names: BlockScale?,
    val nameDim: Int,
    /** Every grouping by its name, with the keys of its groups: a column each. */
    val groupings: List<Pair<String, List<String>>>,
    val valueKeys: List<String>,
) {
    private val contextIndex = contextKeys.withIndex().associate { (i, k) -> k to i }
    internal val valueIndex = valueKeys.withIndex().associate { (i, k) -> k to i }
    private val groupIndex = groupings.map { (_, keys) -> keys.withIndex().associate { (i, k) -> k to i } }

    val itemWidth: Int = 1 + blocks.sumOf { 1 + it.dim } + contextKeys.size

    /** Where each block's mark of having it lies among an item's features; its vector follows the mark. */
    val blockOffsets: List<Int> = blocks.runningFold(1) { at, b -> at + 1 + b.dim }.dropLast(1)

    /** The blocks a text of a work is read in — the title, the description, the notes, the site's lines — of vectors of [dim]: indices of [blocks]. */
    fun textBlocks(dim: Int): List<Int> = blocks.indices.filter { blocks[it].name.startsWith("text:") && blocks[it].dim == dim }
    val valueWidth: Int = 2 + nameDim + groupings.sumOf { it.second.size }

    /** Whether the task can be read by this layout: its blocks of the same names and its names of the same length. */
    fun reads(task: SuggestionTask): Boolean = task.values.cols == nameDim && readsItems(task)

    /** Whether the items of [task] are read by this layout: every block it knows of the length it knows — whatever its values are. */
    fun readsItems(task: SuggestionTask): Boolean {
        val dims = blocks.associate { it.name to it.dim }
        return task.inputs.all { b -> dims[b.name]?.let { it == b.vectors.cols } ?: true }
    }

    /**
     * The features of every item of the task ([ItemFeatures]): its blocks the task's own — the rows
     * of the items that have them, on the card too when the task's are there — nothing copied.
     */
    internal fun features(task: SuggestionTask): ItemFeatures {
        val n = task.itemCount
        val byName = task.inputs.associateBy { it.name }
        // Of every item, how many blocks of the layout it has: their marks among its ones
        val marked = IntArray(n)
        val dense = blocks.indices.mapNotNull { k ->
            val block = byName[blocks[k].name] ?: return@mapNotNull null
            block.rows.forEach { marked[it]++ }
            ItemFeatures.Dense(block.vectors, block.rows, blockOffsets[k], blocks[k].scale, block.card)
        }
        // The task's context keys as columns of this layout
        val base = 1 + blocks.sumOf { 1 + it.dim }
        val columns = IntArray(task.contextCount) { contextIndex[task.contextKeys[it]]?.let { c -> base + c } ?: -1 }
        // The constant, the marks of the blocks the item has, its values of the other facets each once: in
        // an array of its own, no list of boxed numbers made of every item
        val filled = IntArray(n) { 1 }
        val ones = Array(n) { i -> IntArray(1 + marked[i] + task.context[i].size) }
        dense.forEach { b -> b.items.forEach { i -> ones[i][filled[i]++] = b.mark } }
        for (i in 0 until n) {
            val own = ones[i]
            var at = filled[i]
            val marks = at
            for (c in task.context[i]) {
                val column = columns[c]
                if (column < 0) continue
                var seen = false
                for (q in marks until at) if (own[q] == column) {
                    seen = true
                    break
                }
                if (!seen) own[at++] = column
            }
            if (at < own.size) ones[i] = own.copyOf(at)
        }
        return ItemFeatures(n, itemWidth, dense, ones)
    }

    /** The features of every value of the task, in its order. */
    fun values(task: SuggestionTask): Matrix {
        val m = task.valueCount
        val out = Matrix(m, valueWidth)
        val row = FloatArray(valueWidth)
        val name = FloatArray(nameDim)
        val groupingIndex = task.groupings.associateBy { it.name }
        for (v in 0 until m) {
            row.fill(0f)
            row[0] = 1f
            task.values.held.take(name, 0, task.values.row(v), nameDim)
            if (names != null && name.any { it != 0f }) {
                row[1] = 1f
                for (q in 0 until nameDim) row[2 + q] = name[q] * names.scale
            }
            var at = 2 + nameDim
            groupings.forEachIndexed { k, (g, keys) ->
                // The task's groups by their keys: one this layout does not know has no column
                groupingIndex[g]?.let { grouping ->
                    grouping.of[v].forEach { group -> groupIndex[k][grouping.groups[group]]?.let { row[at + it] = 1f } }
                }
                at += keys.size
            }
            out.held.put(row, 0, out.row(v), valueWidth)
        }
        return out
    }

    /** The index in [valueKeys] of every value of the task; -1 for one the fitted task did not have. */
    fun valueIndices(task: SuggestionTask): IntArray = IntArray(task.valueCount) { valueIndex[task.valueKeys[it]] ?: -1 }

    /**
     * Where every feature of an item of this layout was in the [old] one, and by what its weight
     * is multiplied there to weigh the same: the constant; a block of the same name and length —
     * its mark, its coordinates by the ratio of the scales, so a feature times its weight stays as it
     * was; a value of the context by its key. -1: a feature the old layout had not.
     */
    fun itemFeaturesFrom(old: Design): Pair<IntArray, FloatArray> {
        val at = IntArray(itemWidth) { -1 }
        val factor = FloatArray(itemWidth) { 1f }
        at[0] = 0
        val oldBlocks = old.blocks.withIndex().associate { (i, b) -> b.name to i }
        blocks.forEachIndexed { i, b ->
            val j = oldBlocks[b.name]?.takeIf { old.blocks[it].dim == b.dim } ?: return@forEachIndexed
            val here = blockOffsets[i]
            val there = old.blockOffsets[j]
            at[here] = there
            for (q in 0 until b.dim) {
                at[here + 1 + q] = there + 1 + q
                factor[here + 1 + q] = old.blocks[j].scale / b.scale
            }
        }
        val base = 1 + blocks.sumOf { 1 + it.dim }
        val oldBase = 1 + old.blocks.sumOf { 1 + it.dim }
        contextKeys.forEachIndexed { c, key -> old.contextIndex[key]?.let { at[base + c] = oldBase + it } }
        return at to factor
    }

    /** As [itemFeaturesFrom], of a value's features: the constant, its name's mark and coordinates, its groups by their keys. */
    fun valueFeaturesFrom(old: Design): Pair<IntArray, FloatArray> {
        val at = IntArray(valueWidth) { -1 }
        val factor = FloatArray(valueWidth) { 1f }
        at[0] = 0
        if (names != null && old.names != null && nameDim == old.nameDim) {
            at[1] = 1
            for (q in 0 until nameDim) {
                at[2 + q] = 2 + q
                factor[2 + q] = old.names.scale / names.scale
            }
        }
        var here = 2 + nameDim
        val oldAt = HashMap<Pair<String, String>, Int>()
        var there = 2 + old.nameDim
        old.groupings.forEach { (g, keys) -> keys.forEachIndexed { k, key -> oldAt[g to key] = there + k }; there += keys.size }
        groupings.forEach { (g, keys) ->
            keys.forEachIndexed { k, key -> oldAt[g to key]?.let { at[here + k] = it } }
            here += keys.size
        }
        return at to factor
    }

    fun write(out: DataOutputStream) {
        out.writeInt(blocks.size)
        blocks.forEach { writeScale(out, it) }
        writeStrings(out, contextKeys)
        out.writeInt(nameDim)
        out.writeBoolean(names != null)
        names?.let { writeScale(out, it) }
        out.writeInt(groupings.size)
        groupings.forEach { (g, keys) -> out.writeUTF(g); writeStrings(out, keys) }
        writeStrings(out, valueKeys)
    }

    companion object {
        /** The layout of a task: its blocks some item has, its context, its values' names and groups. */
        fun of(task: SuggestionTask): Design {
            val named = (0 until task.valueCount).filter { v -> (0 until task.values.cols).any { task.values.held[task.values.row(v) + it] != 0f } }
            val names = BlockScale.of("names", task.values, named.toIntArray())
            return Design(
                task.inputs.mapNotNull { BlockScale.of(it.name, it.vectors) }, task.contextKeys, names, task.values.cols,
                task.groupings.map { it.name to it.groups }, task.valueKeys,
            )
        }

        fun read(input: DataInputStream): Design {
            val blocks = List(input.readInt()) { readScale(input) }
            val context = readStrings(input)
            val nameDim = input.readInt()
            val names = if (input.readBoolean()) readScale(input) else null
            val groupings = List(input.readInt()) { input.readUTF() to readStrings(input) }
            return Design(blocks, context, names, nameDim, groupings, readStrings(input))
        }

        /** The name, the scale and a centre of nought of the block's length: the bytes of a layout as written when blocks were centred. */
        private fun writeScale(out: DataOutputStream, s: BlockScale) {
            out.writeUTF(s.name)
            out.writeFloat(s.scale)
            writeFloats(out, FloatArray(s.dim))
        }

        private fun readScale(input: DataInputStream): BlockScale {
            val name = input.readUTF()
            val scale = input.readFloat()
            val center = readFloats(input)
            check(center.all { it == 0f }) { "$name: a layout of centred vectors, no longer read" }
            return BlockScale(name, center.size, scale)
        }

        /**
         * The numbers as one block of bytes, big-endian as [DataOutputStream] writes them: a model of a
         * facet of thousands of characters is millions of them, and a number at a time through the
         * compressing stream is most of the time a page waits for a work's chances.
         */
        fun writeFloats(out: DataOutputStream, a: FloatArray) {
            out.writeInt(a.size)
            val bytes = ByteBuffer.allocate(a.size * Float.SIZE_BYTES)
            bytes.asFloatBuffer().put(a)
            out.write(bytes.array())
        }

        fun readFloats(input: DataInputStream): FloatArray {
            val bytes = ByteArray(input.readInt() * Float.SIZE_BYTES)
            input.readFully(bytes)
            return FloatArray(bytes.size / Float.SIZE_BYTES).also { ByteBuffer.wrap(bytes).asFloatBuffer().get(it) }
        }

        // Strings longer than writeUTF takes (65535 bytes) are not keys; written as bytes all the same
        fun writeStrings(out: DataOutputStream, list: List<String>) {
            out.writeInt(list.size)
            list.forEach { s ->
                val bytes = s.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }

        fun readStrings(input: DataInputStream): List<String> = List(input.readInt()) {
            val bytes = ByteArray(input.readInt())
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }
}
