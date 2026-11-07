package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.source.FacetDef
import java.util.stream.IntStream

/**
 * THE CHANCES A WORK IS SHOWN WITH, made of the scores the model gives a facet's works: of every work the values it is
 * told of and the few it is offered besides, put among the chances of the chain ([keep]).
 */
internal object ShownChances {
    /** Nanoseconds in a millisecond: the laps of [keep] are told in milliseconds. */
    private const val NANOS_IN_MILLI = 1_000_000

    /** No value asked: of an item all named, the scores of none but its own. */
    private val NONE = IntArray(0)

    /**
     * The chances of the [facet] of every work of [data] put among [made] — over the drafts, which keep the model's
     * chance — and, when [compare], the values the model alone gives each (no draft among them): what a refit compares;
     * none made of the chain, which compares nothing.
     *
     * OF EVERY WORK THE SCORES OF THE VALUES IT IS SHOWN WITH ALONE: of those it is told of — its own, the site's
     * examples, the rejected ([SuggestionScores.scores]) — and of the others of those it may have
     * ([SuggestionTask.allowed], one array for every work of the same universes) the few chosen of its row as the
     * suggester hands it over, a row at a time ([SuggestionScores.scores] with a reader), their chances made of them.
     * Every chance of every character of a facet of seven thousand, of every one of tens of thousands of works, was six
     * seconds of a reading.
     */
    fun keep(
        made: MadeChances,
        facet: FacetDef,
        data: FacetData,
        scores: SuggestionScores,
        compare: Boolean = false,
        laps: Laps? = null,
    ): Map<String, Set<String>> {
        val given = HashMap<String, Set<String>>()
        val task = data.task
        // The time of its parts: what each work is told of, what it is shown with besides — chosen by the
        // suggester of what it may have — the scores of what it is told of, the chances made, the putting.
        // EVERY WORK AT ONCE: what grows with the works and the values — a row of every value — is the
        // suggester's, a row at a time or on the card; a call of it a chunk of works was 38 of fanfics.me
        // a facet, each sending and taking back
        var at = System.nanoTime()
        val rows = IntArray(data.ids.size) { it }
        val told = Array(rows.size) { toldOf(data, rows[it]) }
        // Neither what it is told of nor what it may not have after all is offered besides
        val closed = rows.mapIndexed { r, i -> (told[r] + task.barred[i]).also { it.sort() } }
        val telling = System.nanoTime() - at
        at = System.nanoTime()
        // What each work is shown with besides, chosen of its row as the suggester hands it over — a row of
        // every value of every work never kept, of the tags thousands of values a work: the likely, and the
        // likeliest of the rest, likely or not, to offer the work at least so many the site does not give
        // ([ModelValues.offered]), chosen of the values by their places. Of a work all named by the user's word, none
        val columns = rows.map { i -> if (data.complete[i]) NONE else task.allowed[i] }
        val open = scores.shown(rows, columns, closed, ModelValues.OFFERED)
        val choosing = System.nanoTime() - at
        at = System.nanoTime()
        val own = scores.scores(rows, told.asList())
        val scoringOwn = System.nanoTime() - at
        at = System.nanoTime()
        // The chances of each work made side by side on every core; put among the chances after, one work after another
        val of = arrayOfNulls<FewChances>(rows.size)
        IntStream.range(0, rows.size).parallel().forEach { r ->
            val i = rows[r]
            of[r] = chancesOf(data.ids[i], facet.key, scores, open[r], told[r], own[r], data, i)
        }
        val making = System.nanoTime() - at
        at = System.nanoTime()
        rows.forEachIndexed { r, i ->
            val id = data.ids[i]
            val chances = of[r]!!
            if (compare && facet.infer) given[id] = ModelValues.given(chances).toSet()
            put(made, id, facet.key, chances)
        }
        val putting = System.nanoTime() - at
        laps?.took("told", telling / NANOS_IN_MILLI)
        laps?.took("chosen", choosing / NANOS_IN_MILLI)
        laps?.took("scores of the told", scoringOwn / NANOS_IN_MILLI)
        laps?.took("made", making / NANOS_IN_MILLI)
        laps?.took("put", putting / NANOS_IN_MILLI)
        return given
    }

    /**
     * The work's [chances] of the [facet] put among [made], over its drafts: a value drafted keeps where it was drafted
     * from beside the model's chance of it; a draft the model gives no chance of stays as it was.
     */
    private fun put(made: MadeChances, id: String, facet: String, chances: FewChances) {
        val byFacet = made.getOrPut(id) { HashMap() }
        val drafts = byFacet[facet]?.values?.filter { it.drafted }.orEmpty()
        byFacet[facet] =
            if (drafts.isEmpty()) chances
            else {
                val out = LinkedHashMap(chances)
                drafts.forEach { d -> out[d.key] = out[d.key]?.copy(draftedFrom = d.draftedFrom) ?: d }
                FewChances(out.values.toTypedArray())
            }
    }

    /**
     * The values item [i] is told of, each once, in their order: of an item all named by the user's word its own alone;
     * else its own, the site's examples, the values the user rejected.
     */
    private fun toldOf(data: FacetData, i: Int): IntArray {
        val own = data.held[i]
        val out = if (data.complete[i]) own.copyOf() else own + data.task.examples[i] + data.task.rejected[i]
        out.sort()
        return out.distinct().toIntArray()
    }

    /**
     * What is made of item [i]'s chances: of every value it has, of every value the user rejected (shown crossed out,
     * in its place by its chance), every one the site gives it as an example — offered to answer on whatever its
     * chance, shown with it — and of the values it lacks those it more likely has than not and the likeliest others
     * offered besides; of a facet that says which values a work may have ([SuggestionTask.allowed]: a work's characters
     * are of its universes, its pairings of its characters) of those alone. Not every value it may have: of a work of a
     * large universe thousands of characters — the user choosing among them gets every one at the asking
     * ([WorkCandidates.candidates]).
     *
     * Of the [scores]: [told] — the values it is told of ([toldOf]) — scored [own]; [open] what it is shown with
     * besides, of the values it may have ([SuggestionTask.allowed], every value when it says none) — their places among
     * them and their scores.
     */
    private fun chancesOf(
        id: String,
        facet: String,
        scores: SuggestionScores,
        open: Shown,
        told: IntArray,
        own: FloatArray,
        data: FacetData,
        i: Int,
    ): FewChances {
        val has = data.held[i]
        fun chance(v: Int, score: Float) = Chance(id, facet, data.values[v], v in has, scores.chance(score).toDouble())
        // All named by the user's word: the item has its own values and is offered no other.
        // The site's own are shown to answer on, unlikely as they may be; a value the user rejected keeps its
        // chance: it is shown crossed out, in its place by it
        if (data.complete[i]) return FewChances(Array(told.size) { chance(told[it], own[it]) })
        val allowed = data.task.allowed[i]
        val places = open.places
        // In the order of the values: those told, then the open ones among them
        val values = IntArray(told.size + places.size)
        val of = FloatArray(values.size)
        told.forEachIndexed { k, v ->
            values[k] = v
            of[k] = own[k]
        }
        places.forEachIndexed { k, p ->
            values[told.size + k] = allowed?.get(p) ?: p
            of[told.size + k] = open.scores[k]
        }
        val order = values.indices.sortedBy { values[it] }
        return FewChances(Array(values.size) { chance(values[order[it]], of[order[it]]) })
    }
}
