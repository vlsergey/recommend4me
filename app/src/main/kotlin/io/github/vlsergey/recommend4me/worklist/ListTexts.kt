package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.vector.Vectors

/**
 * THE TEXTS OF AN ITEM ITS DOCUMENT OF THE INDEX OF THE LIST HOLDS WHOLE, AND THE VECTORS OF ITS TEXTS (owner,
 * 2026-11-22: "everything the search of the duplicates needs taken from the index"; "every text — the fields, not the
 * texts of the chapters — in the index of Lucene"): every text of its own as the user corrected it ([textKeys]), and
 * the vectors of its title and its description by the encoder of now ([vectorKeys]), each of unit length, a byte a
 * number with its scale ([ByteMatrix.pack]) — what a computation over every work reads in one pass of the index, no
 * database read: of the 1.4 million works of ficbook the texts read of the database, a seek of the disk a row, took
 * twelve minutes of a search (2026-11-22).
 */
object ListTexts {

    /** The key of the description among the texts of a source of the [schema]; none of a source of none. */
    fun descriptionOf(schema: SourceSchema): String? = schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key

    /**
     * The texts a document holds whole, by their keys: every text the site gives a work — its own fields, not the texts
     * of its chapters — and the line of the characters.
     */
    fun textKeys(schema: SourceSchema): List<String> =
        (schema.texts.map { it.key } + listOfNotNull(schema.universeLine)).distinct()

    /** The texts a document holds the vectors of, by their keys: the title and the description. */
    fun vectorKeys(schema: SourceSchema): List<String> = listOfNotNull(TextVectors.TITLE, descriptionOf(schema))

    /** The vector as a document holds it, of its half-precision numbers as kept ([Vectors.fromHalf]). */
    fun packedHalves(halves: ByteArray): ByteArray = packed(Vectors.fromHalf(halves))

    /** The vector [v] as a document holds it: of unit length, a byte a number with its scale. */
    fun packed(v: FloatArray): ByteArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val length = kotlin.math.sqrt(norm).toFloat()
        val unit = if (length > 0f) FloatArray(v.size) { v[it] / length } else v
        return ByteMatrix.pack(unit)
    }

    /** The field of the doc values of the text [key] held whole ([textKeys]): its UTF-8. */
    fun textField(key: String) = "$TEXT$key"

    /** The key of the text of the field [name]; null of a field of no text. */
    fun textKeyOf(name: String): String? = name.takeIf { it.startsWith(TEXT) }?.substring(TEXT.length)

    /** Whether the field [name] holds a vector of a text, by any encoder. */
    fun isVectorField(name: String): Boolean = name.startsWith(VECTOR)

    /**
     * The field of the doc values of the vector of the text [key] by the [encoder] ([vectorKeys]): of unit length, a
     * byte a number with its scale ([packed]).
     */
    fun vectorField(encoder: String, key: String) = "$VECTOR$encoder:$key"

    private const val TEXT = "t:"
    private const val VECTOR = "u:"
}
