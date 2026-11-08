package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.suggestion.InputBlock

/**
 * A WAY THE SITES' LINES OF CHARACTERS AND PAIRINGS ARE GIVEN TO THE MODELS THAT LEARN THE WORKS' CHARACTERS AND
 * PAIRINGS OF THEM ([FacetSuggester][io.github.vlsergey.recommend4me.suggestion.FacetSuggester]): what the works' said
 * casts ([SaidCast]) and lines ([CastWorks]) make of the task of such a facet — its inputs, the columns of its context,
 * the values each work's line names ([CastRead]). Every facet of a source whose values the site's line speaks of is
 * read with one of them, chosen by the user on the page of the models.
 */
interface CastReader {
    /** Stable: the choice of the user is kept by it; its title is the plugin's word "title". */
    val id: String

    /**
     * The texts of a work's [cast] this way reads the vectors of: encoded in the background before it reads them, as
     * the texts of the works are ([CastWorks.vectors]); none of a way that reads no vectors of its own.
     */
    fun encoded(cast: SaidCast): List<String> = emptyList()

    /** What the [works] make of the task of their characters or their pairings. */
    fun read(works: CastWorks): CastRead
}

/**
 * The works of a task of characters or pairings, a row of each, as a [CastReader] reads them.
 *
 * - [casts]: what each work's line says ([SaidCast.NONE] of a work without one);
 * - [line]: the line as the site writes it, encoded whole as a text — a row of every work that has it; null when no
 *   work has;
 * - [entries]: the entries of the line as the site keeps them, of every work the columns of the context they are
 *   ("<facet>\u0001<key>"), as the model read them so far;
 * - [vectors]: the texts the way asked for ([CastReader.encoded]), those encoded so far — one that is not yet is
 *   missing;
 * - [characters]: of every work, the characters of its universes by every name each goes by (character to names), one
 *   map for every work of the same universes; null of a work of no universe known;
 * - [paired]: the task's values are pairings of those characters
 *   ([UniverseFacets.pairing][io.github.vlsergey.recommend4me.universe.UniverseFacets.pairing]), not the characters.
 */
class CastWorks(
    val casts: List<SaidCast>,
    val line: InputBlock?,
    val entries: List<List<String>>,
    val vectors: Map<String, FloatArray>,
    val characters: List<Map<String, List<String>>?>,
    val paired: Boolean,
) {
    val count: Int
        get() = casts.size

    init {
        require(entries.size == count && characters.size == count) {
            "$count casts, ${entries.size} lists of entries, ${characters.size} of characters"
        }
        require(line == null || line.rows.all { it in 0 until count }) { "A line of a work not of the $count" }
    }
}

/**
 * What the works' lines make of the task, in its rows: [inputs] — blocks of vectors, a row of each work that has one,
 * their names stable (the model's weights of a block are kept by it), those of a text named "text:…"; [context] — of
 * every work, the columns of its context, any strings, those of other ways told apart by their own beginnings; [named]
 * — of every work, the task's values its line names (characters, or pairings when [CastWorks.paired]): a witness the
 * model weighs as the site's word on the value
 * ([SuggestionTask.examples][io.github.vlsergey.recommend4me.suggestion.SuggestionTask.examples]), never a value the
 * work is taught to have.
 */
class CastRead(val inputs: List<InputBlock>, val context: List<List<String>>, val named: List<List<String>>) {
    init {
        require(context.size == named.size) { "${context.size} contexts, ${named.size} lists of values named" }
    }
}
