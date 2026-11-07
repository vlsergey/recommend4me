package io.github.vlsergey.recommend4me.onnx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * AN ONNX GRAPH MADE IN CODE, written out as the bytes of a ModelProto ([Onnx.session]): a computation of the
 * application's own — a loss and its gradient — run by ONNX Runtime on the card, where no model file carries it. Only
 * what such a graph needs of the format: nodes of the standard operators with their attributes, typed inputs and
 * outputs of named or fixed dimensions, constant tensors. The field numbers are those of onnx.proto.
 */
class OnnxGraph(private val name: String) {
    private val nodes = ArrayList<ByteArray>()
    private val inputs = ArrayList<ByteArray>()
    private val outputs = ArrayList<ByteArray>()
    private val constants = ArrayList<ByteArray>()

    /** An input of the graph; a dimension is a number or the name of one the run gives. */
    fun input(name: String, type: Int, vararg dims: Any) = apply { inputs += valueInfo(name, type, dims) }

    fun output(name: String, type: Int, vararg dims: Any) = apply { outputs += valueInfo(name, type, dims) }

    /** A tensor of floats known when the graph is made. */
    fun constant(name: String, values: FloatArray, vararg dims: Long) = apply {
        val raw =
            ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { b -> values.forEach { b.putFloat(it) } }
                .array()
        constants += tensor(name, FLOAT, dims, raw)
    }

    /**
     * A tensor of floats known when the graph is made, read from a buffer — a matrix of data, without a copy of it on
     * the heap first.
     */
    fun constant(name: String, values: FloatBuffer, vararg dims: Long) = apply {
        val raw = ByteBuffer.allocate(values.remaining() * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        raw.asFloatBuffer().put(values.duplicate())
        constants += tensor(name, FLOAT, dims, raw.array())
    }

    /**
     * A tensor of floats the graph does not carry: the session is handed it when it is opened ([Onnx.onCard]), from the
     * memory it lies in — a matrix of data not copied into the graph's bytes, nor parsed out of them again. Written as
     * external data (onnx.proto's `data_location = EXTERNAL`) of no file: the runtime takes it from what it is handed,
     * by name.
     */
    fun supplied(name: String, vararg dims: Long) = apply {
        constants +=
            Proto()
                .apply {
                    dims.forEach { varint(TensorField.DIMS, it) }
                    varint(TensorField.DATA_TYPE, FLOAT.toLong())
                    string(TensorField.NAME, name)
                    message(TensorField.EXTERNAL_DATA, entry("location", SUPPLIED))
                    message(
                        TensorField.EXTERNAL_DATA,
                        entry("length", (dims.fold(1L, Long::times) * Float.SIZE_BYTES).toString()),
                    )
                    varint(TensorField.DATA_LOCATION, DATA_EXTERNAL)
                }
                .bytes()
    }

    /** A tensor of 64-bit integers known when the graph is made. */
    fun constant(name: String, values: LongArray, vararg dims: Long) = apply {
        val raw =
            ByteBuffer.allocate(values.size * Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .also { b -> values.forEach { b.putLong(it) } }
                .array()
        constants += tensor(name, INT64, dims, raw)
    }

    /** A node of the standard operator [op]; an attribute is an Int, a Long, a Float or a LongArray. */
    fun node(op: String, inputs: List<String>, outputs: List<String>, attributes: Map<String, Any> = emptyMap()) =
        apply {
            nodes +=
                Proto()
                    .apply {
                        inputs.forEach { string(NodeField.INPUT, it) }
                        outputs.forEach { string(NodeField.OUTPUT, it) }
                        string(NodeField.NAME, "${op}_${nodes.size}")
                        string(NodeField.OP_TYPE, op)
                        attributes.forEach { (n, v) -> message(NodeField.ATTRIBUTE, attribute(n, v)) }
                    }
                    .bytes()
        }

    /** The ModelProto of the graph, of the operators of the standard set at [opset]. */
    fun bytes(opset: Int): ByteArray {
        val graph =
            Proto()
                .apply {
                    nodes.forEach { message(GraphField.NODE, it) }
                    string(GraphField.NAME, name)
                    constants.forEach { message(GraphField.INITIALIZER, it) }
                    inputs.forEach { message(GraphField.INPUT, it) }
                    outputs.forEach { message(GraphField.OUTPUT, it) }
                }
                .bytes()
        return Proto()
            .apply {
                varint(ModelField.IR_VERSION, IR_VERSION)
                string(ModelField.PRODUCER_NAME, "recommend4me")
                message(ModelField.GRAPH, graph)
                message(ModelField.OPSET_IMPORT, Proto().apply { varint(OPSET_VERSION, opset.toLong()) }.bytes())
            }
            .bytes()
    }

    private fun valueInfo(name: String, type: Int, dims: Array<out Any>): ByteArray {
        val shape =
            Proto()
                .apply {
                    dims.forEach { d ->
                        message(
                            SHAPE_DIM,
                            Proto()
                                .apply {
                                    if (d is String) string(DIM_PARAM, d) else varint(DIM_VALUE, (d as Number).toLong())
                                }
                                .bytes(),
                        )
                    }
                }
                .bytes()
        val tensorType =
            Proto()
                .apply {
                    varint(TENSOR_TYPE_ELEM_TYPE, type.toLong())
                    message(TENSOR_TYPE_SHAPE, shape)
                }
                .bytes()
        return Proto()
            .apply {
                string(VALUE_INFO_NAME, name)
                message(VALUE_INFO_TYPE, Proto().apply { message(TYPE_TENSOR_TYPE, tensorType) }.bytes())
            }
            .bytes()
    }

    private fun tensor(name: String, type: Int, dims: LongArray, raw: ByteArray): ByteArray =
        Proto()
            .apply {
                dims.forEach { varint(TensorField.DIMS, it) }
                varint(TensorField.DATA_TYPE, type.toLong())
                string(TensorField.NAME, name)
                bytes(TensorField.RAW_DATA, raw)
            }
            .bytes()

    private fun attribute(name: String, value: Any): ByteArray =
        Proto()
            .apply {
                string(AttributeField.NAME, name)
                when (value) {
                    is Float -> {
                        fixed32(AttributeField.F, value.toRawBits())
                        varint(AttributeField.TYPE, ATTRIBUTE_FLOAT)
                    }
                    is Int -> {
                        varint(AttributeField.I, value.toLong())
                        varint(AttributeField.TYPE, ATTRIBUTE_INT)
                    }
                    is Long -> {
                        varint(AttributeField.I, value)
                        varint(AttributeField.TYPE, ATTRIBUTE_INT)
                    }
                    is LongArray -> {
                        value.forEach { varint(AttributeField.INTS, it) }
                        varint(AttributeField.TYPE, ATTRIBUTE_INTS)
                    }
                    else -> error("An attribute of ${value.javaClass}")
                }
            }
            .bytes()

    /** The protocol buffers' wire format: a field is its number and wire type, then its value. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        /** A base-128 varint: seven bits a byte, the lowest first, the high bit of every byte but the last set. */
        private fun raw(v: Long) {
            var x = v
            while (x and SEVEN_BITS.inv() != 0L) {
                out.write(((x and SEVEN_BITS) or MORE).toInt())
                x = x ushr BITS_A_BYTE
            }
            out.write(x.toInt())
        }

        /** The key of a field: its number above the three bits of its wire type. */
        private fun key(field: Int, wireType: Int) = raw(((field shl WIRE_TYPE_BITS) or wireType).toLong())

        fun varint(field: Int, v: Long) {
            key(field, WIRE_VARINT)
            raw(v)
        }

        fun fixed32(field: Int, bits: Int) {
            key(field, WIRE_FIXED32)
            for (k in 0 until Int.SIZE_BYTES) out.write((bits ushr (Byte.SIZE_BITS * k)) and BYTE_MASK)
        }

        fun bytes(field: Int, b: ByteArray) {
            key(field, WIRE_LENGTH_DELIMITED)
            raw(b.size.toLong())
            out.write(b)
        }

        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

        fun message(field: Int, b: ByteArray) = bytes(field, b)

        fun bytes(): ByteArray = out.toByteArray()

        companion object {
            /** The wire types: a varint, four bytes, a length and as many bytes. */
            private const val WIRE_VARINT = 0
            private const val WIRE_FIXED32 = 5
            private const val WIRE_LENGTH_DELIMITED = 2

            /** The bits of a key the wire type takes. */
            private const val WIRE_TYPE_BITS = 3

            /** The bits of a number a byte of a varint carries, and the bit that says another byte follows. */
            private const val BITS_A_BYTE = 7
            private const val SEVEN_BITS = 0x7FL
            private const val MORE = 0x80L

            /** The low byte of an int. */
            private const val BYTE_MASK = 0xFF
        }
    }

    /** The fields of TensorProto. */
    private object TensorField {
        const val DIMS = 1
        const val DATA_TYPE = 2
        const val NAME = 8
        const val RAW_DATA = 9
        const val EXTERNAL_DATA = 13
        const val DATA_LOCATION = 14
    }

    /** The fields of NodeProto. */
    private object NodeField {
        const val INPUT = 1
        const val OUTPUT = 2
        const val NAME = 3
        const val OP_TYPE = 4
        const val ATTRIBUTE = 5
    }

    /** The fields of GraphProto. */
    private object GraphField {
        const val NODE = 1
        const val NAME = 2
        const val INITIALIZER = 5
        const val INPUT = 11
        const val OUTPUT = 12
    }

    /** The fields of ModelProto. */
    private object ModelField {
        const val IR_VERSION = 1
        const val PRODUCER_NAME = 2
        const val GRAPH = 7
        const val OPSET_IMPORT = 8
    }

    /** The fields of AttributeProto. */
    private object AttributeField {
        const val NAME = 1
        const val F = 2
        const val I = 3
        const val INTS = 8
        const val TYPE = 20
    }

    companion object {
        /** TensorProto.DataType */
        const val FLOAT = 1
        const val INT64 = 7
        const val DOUBLE = 11

        /** The version of the format written: the one of the operator sets ONNX Runtime 1.20 reads. */
        private const val IR_VERSION = 8L

        /** AttributeProto.AttributeType */
        private const val ATTRIBUTE_FLOAT = 1L
        private const val ATTRIBUTE_INT = 2L
        private const val ATTRIBUTE_INTS = 7L

        /** TensorProto.DataLocation: the data lies outside the graph. */
        private const val DATA_EXTERNAL = 1L

        /** The "file" a [supplied] tensor's data is in: none — a name only, for the format to have one. */
        private const val SUPPLIED = "supplied"

        /** OperatorSetIdProto's version; StringStringEntryProto's key and value. */
        private const val OPSET_VERSION = 2
        private const val ENTRY_KEY = 1
        private const val ENTRY_VALUE = 2

        /** TensorShapeProto's dim; a Dimension's dim_value and dim_param. */
        private const val SHAPE_DIM = 1
        private const val DIM_VALUE = 1
        private const val DIM_PARAM = 2

        /** TypeProto.Tensor's elem_type and shape; TypeProto's tensor_type; ValueInfoProto's name and type. */
        private const val TENSOR_TYPE_ELEM_TYPE = 1
        private const val TENSOR_TYPE_SHAPE = 2
        private const val TYPE_TENSOR_TYPE = 1
        private const val VALUE_INFO_NAME = 1
        private const val VALUE_INFO_TYPE = 2

        /** A StringStringEntryProto: a [key] and its [value]. */
        private fun entry(key: String, value: String): ByteArray =
            Proto()
                .apply {
                    string(ENTRY_KEY, key)
                    string(ENTRY_VALUE, value)
                }
                .bytes()
    }
}
