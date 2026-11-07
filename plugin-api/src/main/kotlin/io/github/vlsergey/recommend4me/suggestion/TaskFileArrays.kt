package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * THE ARRAYS OF A TASK'S FILE ([SuggestionTaskFile]) as it writes and reads them: a list of strings, an array of ints,
 * a list of such arrays, a matrix — its size first, then its elements in order.
 */
internal object TaskFileArrays {
    fun strings(out: DataOutputStream, list: List<String>) {
        out.writeInt(list.size)
        list.forEach(out::writeUTF)
    }

    fun strings(input: DataInputStream): List<String> = List(input.readInt()) { input.readUTF() }

    fun ints(out: DataOutputStream, a: IntArray) {
        out.writeInt(a.size)
        a.forEach(out::writeInt)
    }

    fun ints(input: DataInputStream): IntArray = IntArray(input.readInt()) { input.readInt() }

    fun lists(out: DataOutputStream, list: List<IntArray>) {
        out.writeInt(list.size)
        list.forEach { ints(out, it) }
    }

    fun lists(input: DataInputStream): List<IntArray> = List(input.readInt()) { ints(input) }

    fun matrix(out: DataOutputStream, m: Matrix) {
        out.writeInt(m.rows)
        out.writeInt(m.cols)
        val row = FloatArray(m.cols)
        for (i in 0 until m.rows) {
            m.held.take(row, 0, m.row(i), m.cols)
            row.forEach(out::writeFloat)
        }
    }

    /** Of half precision, written as single: the file reads the same of either. */
    fun matrix(out: DataOutputStream, m: HalfMatrix) {
        out.writeInt(m.rows)
        out.writeInt(m.cols)
        val row = FloatArray(m.cols)
        for (i in 0 until m.rows) {
            m.take(i, row)
            row.forEach(out::writeFloat)
        }
    }

    fun matrix(input: DataInputStream): Matrix {
        val m = Matrix(input.readInt(), input.readInt())
        val row = FloatArray(m.cols)
        for (i in 0 until m.rows) {
            for (j in row.indices) row[j] = input.readFloat()
            m.held.put(row, 0, m.row(i), m.cols)
        }
        return m
    }
}
