package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.suggestion.TaskFileArrays.ints
import io.github.vlsergey.recommend4me.suggestion.TaskFileArrays.lists
import io.github.vlsergey.recommend4me.suggestion.TaskFileArrays.matrix
import io.github.vlsergey.recommend4me.suggestion.TaskFileArrays.strings
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A [SuggestionTask] kept in a file whole, as the application handed it to a suggester: a fitting of real data, made
 * again and measured apart from the application — a profile of it, a change of the model tried on it — as often as
 * needed, without waiting for the data to change.
 */
object SuggestionTaskFile {
    /**
     * 7: an input block of the items that have it alone, with the item of every row; 6: no counts of the values' names
     * in the items' texts; 5: the arrays of the allowed once each, the barred at the end; 4: the drafts and their
     * weight at the end; 3: the names' texts a matrix, their arrays of values once each; 2 and 1 read too, their counts
     * passed over.
     */
    private const val FORMAT = 7

    /** The format since which an input block holds the items that have it alone, with the item of every row. */
    private const val ROWS_OF_ITEMS = 7

    /** The format since which the counts of the values' names in the items' texts are not written. */
    private const val NO_NAME_COUNTS = 6

    /** The format since which the arrays of the allowed are written once each, and the barred at the end. */
    private const val ALLOWED_ONCE = 5

    /** The format since which the drafts and their weight are at the end. */
    private const val DRAFTS = 4

    /** The format since which the names' texts are a matrix, their arrays of values once each. */
    private const val HINT_MATRIX = 3

    /** The format whose arrays a name is judged among hold its own value too; the first held its rivals alone. */
    private const val HINT_WITH_VALUE = 2

    fun write(task: SuggestionTask, file: Path) {
        Files.createDirectories(file.parent)
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(file)).buffered()).use { out ->
            out.writeInt(FORMAT)
            strings(out, task.itemIds)
            out.writeInt(task.inputs.size)
            task.inputs.forEach { b ->
                out.writeUTF(b.name)
                matrix(out, b.vectors)
                ints(out, b.rows)
            }
            matrix(out, task.values)
            strings(out, task.valueKeys)
            listOf(task.assigned, task.confirmed, task.rejected, task.context, task.examples).forEach { lists(out, it) }
            strings(out, task.contextKeys)
            // Every array of the allowed once, each item by its number: one of thousands of characters for every work
            // of a universe
            val allowed = IdentityHashMap<IntArray, Int>()
            task.allowed.forEach { a -> if (a != null) allowed.putIfAbsent(a, allowed.size) }
            out.writeInt(allowed.size)
            allowed.entries.sortedBy { it.value }.forEach { ints(out, it.key) }
            out.writeInt(task.allowed.size)
            task.allowed.forEach { a -> out.writeInt(a?.let(allowed::getValue) ?: -1) }
            out.writeInt(task.groupings.size)
            task.groupings.forEach { g ->
                out.writeUTF(g.name)
                strings(out, g.groups)
                lists(out, g.of)
            }
            out.writeDouble(task.lackWeight)
            // Every array of values the names are judged among once, each name by its number
            val among = IdentityHashMap<IntArray, Int>()
            task.hints.forEach { among.putIfAbsent(it.among, among.size) }
            out.writeInt(among.size)
            among.entries.sortedBy { it.value }.forEach { ints(out, it.key) }
            out.writeInt(task.hints.size)
            task.hints.forEach { h ->
                out.writeInt(h.value)
                out.writeInt(among.getValue(h.among))
            }
            matrix(out, task.hintTexts)
            lists(out, task.drafts)
            out.writeDouble(task.draftWeight)
            lists(out, task.barred)
        }
    }

    fun read(file: Path): SuggestionTask =
        DataInputStream(GZIPInputStream(Files.newInputStream(file)).buffered()).use { input ->
            val format = input.readInt()
            check(format in 1..FORMAT) { "$file: not a task of format $FORMAT or before it" }
            val itemIds = strings(input)
            val inputs = inputs(input, format)
            val values = matrix(input)
            val valueKeys = strings(input)
            val assigned = lists(input)
            val confirmed = lists(input)
            val rejected = lists(input)
            val context = lists(input)
            val examples = lists(input)
            val contextKeys = strings(input)
            if (format < NO_NAME_COUNTS) skipNameCounts(input)
            val allowed = allowed(input, format)
            val groupings = List(input.readInt()) { ValueGrouping(input.readUTF(), strings(input), lists(input)) }
            val lackWeight = input.readDouble()
            val (hints, hintTexts) = hints(input, format)
            // The formats before 4 had no drafts
            val drafts = if (format >= DRAFTS) lists(input) else List(itemIds.size) { IntArray(0) }
            val draftWeight = if (format >= DRAFTS) input.readDouble() else 1.0
            val barred = if (format >= ALLOWED_ONCE) lists(input) else List(itemIds.size) { IntArray(0) }
            SuggestionTask(
                itemIds,
                inputs,
                values,
                valueKeys,
                assigned,
                confirmed,
                rejected,
                context,
                contextKeys,
                allowed,
                groupings,
                lackWeight,
                examples,
                hints,
                hintTexts,
                drafts,
                draftWeight,
                barred,
            )
        }

    /** The input blocks of the items, as the [format] wrote them. */
    private fun inputs(input: DataInputStream, format: Int): List<InputBlock> =
        List(input.readInt()) {
            val name = input.readUTF()
            val vectors = matrix(input)
            // The formats before 7: a row of every item, and whether it has the block
            if (format >= ROWS_OF_ITEMS) InputBlock(name, HalfMatrix.of(vectors), ints(input))
            else InputBlock.of(name, vectors, BooleanArray(input.readInt()) { input.readBoolean() })
        }

    /**
     * Passes over what the formats before 6 wrote: the counts of the values' names in the items' texts, which no model
     * reads now.
     */
    private fun skipNameCounts(input: DataInputStream) =
        repeat(input.readInt()) {
            val size = input.readInt()
            repeat(maxOf(size, 0) * 2) { input.readInt() }
        }

    /** The values every item is allowed (null: every value), as the [format] wrote them. */
    private fun allowed(input: DataInputStream, format: Int): List<IntArray?> =
        if (format >= ALLOWED_ONCE) {
            val arrays = List(input.readInt()) { ints(input) }
            List(input.readInt()) { input.readInt().takeIf { it >= 0 }?.let(arrays::get) }
        } else List(input.readInt()) { if (input.readBoolean()) ints(input) else null }

    /** The names of the values learnt from and their texts' vectors, as the [format] wrote them. */
    private fun hints(input: DataInputStream, format: Int): Pair<List<NameHint>, Matrix> =
        if (format >= HINT_MATRIX) {
            val among = List(input.readInt()) { ints(input) }
            List(input.readInt()) { NameHint(input.readInt(), among[input.readInt()]) } to matrix(input)
        } else {
            // The formats before it: a vector with every name, and the values it is judged among —
            // of the first its rivals alone — an array of every name
            val read =
                List(input.readInt()) {
                    val vector = FloatArray(input.readInt()) { input.readFloat() }
                    val value = input.readInt()
                    val among =
                        ints(input).let {
                            if (format == HINT_WITH_VALUE) it else (it + value).distinct().sorted().toIntArray()
                        }
                    vector to NameHint(value, among)
                }
            val texts = Matrix(read.size, read.firstOrNull()?.first?.size ?: 0)
            read.forEachIndexed { k, (vector, _) -> texts.held.put(vector, 0, texts.row(k), texts.cols) }
            read.map { it.second } to texts
        }
}
