package io.github.vlsergey.recommend4me.onnx

import java.nio.ByteBuffer

/**
 * A GRAPH OF A MODEL FILE GIVEN OTHER OUTPUTS: its bytes read as the fields of onnx.proto's messages, never parsed
 * whole — a model of a gigabyte is its constants, passed over by their lengths —, out of the heap — the file mapped,
 * the graph written into memory of its own the runtime reads ([Onnx.madeOf]) —, and written again with its outputs
 * replaced by those of a graph made in code ([OnnxGraph]), whose nodes and constants are added to it. What the model's
 * own output was made by, and nothing else reads, is left out. So the runtime gives back what is asked of a model, not
 * all it computes: bge-m3's vector is the first piece's hidden state of the last layer, and its output was every
 * piece's (owner, 2026-11-25: a batch of 64 windows of 256 pieces brought back 64 MB of the card for 256 KB used).
 */
/**
 * An output of every piece of a sequence: its row multiplied by [matrix] — [width of the sequence, out], row after row
 * — and [bias] — [out] — added, named [name].
 */
class PiecesOutput(val name: String, val matrix: FloatArray, val bias: FloatArray)

object OnnxSplice {

    /**
     * The model [model] whose output [sequence] — of [batch, pieces, width] — is replaced by its first piece's row,
     * [batch, width], named [output]: in the precision the model computes it in — the half one of a model whose output
     * is cast to floats, the cast left out.
     */
    fun firstPiece(model: ByteBuffer, sequence: String, output: String): ByteBuffer =
        withOutputs(model, sequence, output, null)

    /**
     * The model [model] whose output [sequence] — of [batch, pieces, width] — is replaced by its first piece's row
     * named [first], and, when [pieces] is given, every piece's row multiplied by its matrix and its bias added, named
     * by it: [batch, pieces, out] — a head and a projection of every piece on the card, nothing of a width brought back
     * but what is read. In the precision the model computes in.
     */
    fun withOutputs(model: ByteBuffer, sequence: String, first: String, pieces: PiecesOutput?): ByteBuffer {
        val graph = fieldsOf(model).single { it.number == MODEL_GRAPH }
        val nodes = fieldsOf(model, graph.valueStart, graph.valueEnd).filter { it.number == GRAPH_NODE }
        val maker = nodes.firstOrNull { sequence in stringsOf(model, it, NODE_OUTPUT) }
        val cast = maker?.takeIf { stringsOf(model, it, NODE_OP_TYPE).singleOrNull() == "Cast" }
        val from = cast?.let { stringsOf(model, it, NODE_INPUT).first() } ?: sequence
        val type = if (cast != null) OnnxGraph.FLOAT16 else OnnxGraph.FLOAT
        val added =
            OnnxGraph(first)
                .constant("${first}_place", longArrayOf(0))
                .node("Gather", listOf(from, "${first}_place"), listOf(first), mapOf("axis" to 1L))
                .output(first, type, "batch", "width")
        if (pieces != null) {
            val width = pieces.bias.size.toLong()
            added
                .constant("${pieces.name}_matrix", pieces.matrix, type, pieces.matrix.size / width, width)
                .constant("${pieces.name}_bias", pieces.bias, type, width)
                .node("MatMul", listOf(from, "${pieces.name}_matrix"), listOf("${pieces.name}_product"))
                .node("Add", listOf("${pieces.name}_product", "${pieces.name}_bias"), listOf(pieces.name))
                .output(pieces.name, type, "batch", "pieces", width)
        }
        return replaced(model, graph, added.fields()) { it.number == GRAPH_OUTPUT || it.start == cast?.start }
    }

    /**
     * The [model] of its graph's field [graph] written again: the graph's fields [left] out, the fields [added]
     * appended — a message's fields appended are merged into it. Memory of its own of the new length, out of the heap,
     * copied into by ranges.
     */
    private fun replaced(model: ByteBuffer, graph: Field, added: ByteArray, left: (Field) -> Boolean): ByteBuffer {
        val kept = fieldsOf(model, graph.valueStart, graph.valueEnd).filterNot(left)
        val length = kept.sumOf { (it.valueEnd - it.start).toLong() } + added.size
        val key = varint((MODEL_GRAPH shl WIRE_BITS or LENGTH_DELIMITED).toLong())
        val size = varint(length)
        val total = graph.start.toLong() + key.size + size.size + length + (model.limit() - graph.valueEnd)
        require(total <= Int.MAX_VALUE) { "A model of $total bytes is more than a buffer holds" }
        val out = ByteBuffer.allocateDirect(total.toInt())
        fun put(from: Int, to: Int) {
            out.put(model.slice(from, to - from))
        }
        put(0, graph.start)
        out.put(key)
        out.put(size)
        kept.forEach { put(it.start, it.valueEnd) }
        out.put(added)
        put(graph.valueEnd, model.limit())
        return out.flip()
    }

    /** A field of a message: its number and wire type, where it begins, where its value lies — and the field ends. */
    private class Field(val number: Int, val wireType: Int, val start: Int, val valueStart: Int, val valueEnd: Int)

    /** The fields of the message of [bytes] from [from] to [to], in their order. */
    private fun fieldsOf(bytes: ByteBuffer, from: Int = 0, to: Int = bytes.limit()): List<Field> {
        val out = ArrayList<Field>()
        var at = from
        while (at < to) {
            val start = at
            val (key, afterKey) = readVarint(bytes, at)
            val number = (key ushr WIRE_BITS).toInt()
            val wireType = (key and WIRE_MASK).toInt()
            val (valueStart, valueEnd) =
                when (wireType) {
                    VARINT -> afterKey to readVarint(bytes, afterKey).second
                    FIXED64 -> afterKey to afterKey + Long.SIZE_BYTES
                    FIXED32 -> afterKey to afterKey + Int.SIZE_BYTES
                    LENGTH_DELIMITED -> {
                        val (length, valueAt) = readVarint(bytes, afterKey)
                        valueAt to valueAt + length.toInt()
                    }
                    else -> error("A field of wire type $wireType at $start")
                }
            out += Field(number, wireType, start, valueStart, valueEnd)
            at = valueEnd
        }
        return out
    }

    /** The strings of the field [number] of the message [message] of [bytes]. */
    private fun stringsOf(bytes: ByteBuffer, message: Field, number: Int): List<String> =
        fieldsOf(bytes, message.valueStart, message.valueEnd)
            .filter { it.number == number && it.wireType == LENGTH_DELIMITED }
            .map { f ->
                String(ByteArray(f.valueEnd - f.valueStart).also { bytes.get(f.valueStart, it) }, Charsets.UTF_8)
            }

    /** A base-128 varint at [at]: its value, and where the next byte is. */
    private fun readVarint(bytes: ByteBuffer, at: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var i = at
        while (true) {
            val b = bytes.get(i++).toInt()
            value = value or ((b and LOW_BITS).toLong() shl shift)
            if (b and MORE == 0) return value to i
            shift += VARINT_BITS
        }
    }

    /** The bytes of the varint of [value]. */
    private fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var v = value
        while (v and LOW_BITS.toLong().inv() != 0L) {
            out += ((v and LOW_BITS.toLong()) or MORE.toLong()).toByte()
            v = v ushr VARINT_BITS
        }
        out += v.toByte()
        return out.toByteArray()
    }

    /** The fields of ModelProto, GraphProto and NodeProto read and written here. */
    private const val MODEL_GRAPH = 7
    private const val GRAPH_NODE = 1
    private const val GRAPH_OUTPUT = 12
    private const val NODE_INPUT = 1
    private const val NODE_OUTPUT = 2
    private const val NODE_OP_TYPE = 4

    /** The wire types, and the bits of a key the wire type takes. */
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH_DELIMITED = 2
    private const val FIXED32 = 5
    private const val WIRE_BITS = 3
    private const val WIRE_MASK = 7L

    /** The bits of a number a byte of a varint carries, and the bit that says another byte follows. */
    private const val VARINT_BITS = 7
    private const val LOW_BITS = 0x7F
    private const val MORE = 0x80
}
