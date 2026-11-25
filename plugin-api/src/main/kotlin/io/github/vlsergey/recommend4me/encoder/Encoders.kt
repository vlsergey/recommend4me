package io.github.vlsergey.recommend4me.encoder

import java.awt.image.BufferedImage

/** What a text is to an encoder trained with prefixes: a query searched for, or a passage searched in. */
enum class TextKind {
    QUERY,
    PASSAGE,
}

/**
 * A text encoder: texts of any language to unit vectors in one space. Every vector is stored with the encoder's [id];
 * vectors of another encoder are of another space and are made again.
 */
interface TextEncoder {
    val id: String
    val dim: Int

    /** Whether its files are in place; an encoder that is not ready is skipped. */
    fun ready(): Boolean

    /** Vectors of texts of any length, of unit length; a blank text gets null. */
    fun encode(texts: List<String>, kind: TextKind = TextKind.PASSAGE): List<FloatArray?>

    /**
     * What the windows ([encodeWindows]) are made by: the encoder and the way it cuts a text into them. Every window's
     * vector is stored with it; windows of another are made again, the vectors of whole texts kept.
     */
    val windowsId: String
        get() = id

    /**
     * A vector for every window of every text — the pieces of it the encoder reads, of its own size and overlapping as
     * it chooses — so a long text (a chapter) is a set of vectors rather than their average: what the search finds a
     * scene by, and what the models read of a chapter.
     */
    fun encodeWindows(texts: List<String>): List<List<FloatArray>>

    /**
     * Where every window [encodeWindows] makes of the text lies in it, in characters, in order — overlapping as they
     * do: the piece of the text a window's vector is of, read by a reranker, shown as what was found.
     */
    fun windowSpans(text: String): List<IntRange>

    /** The vectors of every piece of a text ([PieceEncoder]); none of an encoder that makes none. */
    val pieces: PieceEncoder?
        get() = null
}

/**
 * A PROJECTION OF THE PIECES' VECTORS, learnt by the application of its own texts: its rows the directions — of the
 * width of the head's vectors ([PieceEncoder.width]) — a piece's vector is read along; [key] names it, kept with the
 * vectors it made.
 */
class PieceProjection(val key: String, val rows: List<FloatArray>)

/**
 * A text encoded with its pieces: the vector of every window, as [TextEncoder.encodeWindows] gives them, and of every
 * piece.
 */
class EncodedPieces(val windows: List<FloatArray>, val pieces: List<FloatArray>)

/**
 * THE VECTOR OF EVERY PIECE (TOKEN) OF A TEXT, of the context of the window it is read in — so a meaning is found where
 * it is said, a line's pieces each matched with the best piece of a span of the text (owner, 2026-11-25) — made by the
 * same run of the encoder as the windows' vectors.
 */
interface PieceEncoder {
    /** What the pieces are made by — the encoder, its head, how it cuts a text into windows; kept with them. */
    val id: String

    /** The numbers of a piece's vector as the head makes it, before a projection. */
    val width: Int

    /** Whether its files are in place. */
    fun ready(): Boolean

    /**
     * Of every text, the vector of every window and of every piece in order — a piece read in the window where it
     * stands farthest from an edge, of its text on both sides —, the head's vector projected by the [projection]'s rows
     * (as it is, of none), of unit length; a blank text has none.
     */
    fun encode(texts: List<String>, projection: PieceProjection?): List<EncodedPieces>

    /** The vector of every piece of a line searched for, projected and of unit length as [encode] makes them. */
    fun query(line: String, projection: PieceProjection?): List<FloatArray>

    /**
     * Where every piece [encode] makes of the text lies in it, in characters; null of a piece of no characters of its
     * own.
     */
    fun pieceSpans(text: String): List<IntRange?>
}

/**
 * A CROSS-ENCODER (a reranker): reads two texts TOGETHER and says how much the second answers the first. Two vectors
 * compared by their cosine are two texts each read alone, squeezed into a few hundred numbers before they meet; read
 * together, a trope, a kind of hero, a turn of the plot told in other words is seen. Its scores are the model's own
 * numbers: only their order means anything, and only among the scores of one first text.
 */
interface TextReranker {
    /** Stable: kept with every score; scores of another reranker are made again. */
    val id: String

    /** Whether its files are in place; a reranker that is not ready is skipped. */
    fun ready(): Boolean

    /**
     * The texts cut into the reranker's pieces, on every core: the costly part done on the CPU, once for every pair a
     * text is in — a pass reads every work against every graded one.
     */
    fun prepare(texts: List<String>): PreparedTexts

    /**
     * The score of every pair of the [texts] prepared, the first of pair k at [first]`[k]`, the second at
     * [second]`[k]`.
     */
    fun score(texts: PreparedTexts, first: IntArray, second: IntArray): FloatArray

    /**
     * The score of every text of [texts] answering the [query] — a line searched for against the texts of works: the
     * line is short, and a text is read as far as what the line leaves of the reranker's reach, not cut to an equal
     * share of it as a pair of two texts is.
     */
    fun rank(query: String, texts: List<String>): FloatArray = score(texts.map { query to it })

    /** The score of every pair (first, second) of texts. */
    fun score(pairs: List<Pair<String, String>>): FloatArray {
        val distinct = pairs.flatMap { it.toList() }.distinct()
        val at = distinct.withIndex().associate { (i, t) -> t to i }
        return score(
            prepare(distinct),
            IntArray(pairs.size) { at.getValue(pairs[it].first) },
            IntArray(pairs.size) { at.getValue(pairs[it].second) },
        )
    }
}

/** Texts made ready for a reranker by [TextReranker.prepare]: what it reads of them, by their order. */
interface PreparedTexts {
    val size: Int
}

/** A picture made ready for an encoder: decoded, resized, cut — the costly part, done off the encoder's thread. */
interface PreparedPicture

/** A picture encoder: pictures to unit vectors. */
interface ImageEncoder {
    val id: String
    val dim: Int

    fun ready(): Boolean

    /** Runs on the download threads, in parallel. */
    fun prepare(image: BufferedImage): PreparedPicture

    fun encode(pictures: List<PreparedPicture>): List<FloatArray>
}
