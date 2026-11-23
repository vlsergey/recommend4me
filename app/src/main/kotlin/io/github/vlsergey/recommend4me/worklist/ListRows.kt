package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.draft.WorkDrafts
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.part.PartCounts
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant

/**
 * WHAT CHANGES OF A ROW OF THE LIST WITHOUT ITS ITEM CHANGING — of its work: whether it is graded, its prediction, its
 * chance of interest and the two multiplied, the order of the list by the formula ([Interest.expected]); null of none.
 * And whether the user [marked] the work — graded it, marked it of interest, answered on a value of it: the tasks over
 * the works go over the marked apart from the rest (docs/computations.md, 9). Of the chance of interest, the model's
 * own too ([modelInterest]): what stands when the user's mark is taken back.
 */
class RowValues(
    val graded: Boolean,
    val prediction: Double?,
    val interest: Double?,
    val expected: Double?,
    val marked: Boolean = graded,
    val modelInterest: Double? = null,
) {
    companion object {
        /**
         * The values of an item of the [ratings] given at its [version], [predicted] so, [mark]ed of interest so,
         * [answered] on a value or not.
         */
        fun of(
            version: String,
            ratings: List<Rating>?,
            predicted: Predicted?,
            mark: InterestMark?,
            answered: Boolean,
        ): RowValues {
            val chance = Interest.chance(mark?.interesting, predicted?.interest)
            return RowValues(
                graded = ratings.orEmpty().any { it.version == version },
                prediction = predicted?.score,
                interest = chance,
                expected = predicted?.let { Interest.expected(it.score, chance) },
                marked = !ratings.isNullOrEmpty() || mark != null || answered,
                modelInterest = predicted?.interest,
            )
        }
    }
}

/**
 * ONE ITEM AS THE LIST OF WORKS READS IT: the [work] it is of — the item that names it, a work of several items one row
 * of the list — and its [rank] among the items of the work, the one the work is shown with ([WorkClusters.shown]) 0;
 * its dates, its [values], the values of every filter facet of its source as the user sees them ([facets], by the
 * facet's filter id — the site's, the model's kept, the user's word on top), every number with the user's word on top
 * ([numbers], by the number's id), its [title] as the user corrected it, of every facet the source works out the values
 * the models give it beyond the site's own ([model], by the facet's key): kept in the index alone; the sweeps of the
 * tasks over the works that last did it ([stages]); when every kind of data of its work last changed ([dates],
 * [WorkDatum]) — of the outer ones as the databases date them, of the others as the stages that make them did: what the
 * stages take it by ([StageSweeps]); its texts held whole as the user corrected them ([texts], by their keys) and the
 * vectors of its texts ([vectors]: [ListTexts]).
 */
class ListRow(
    val key: ItemKey,
    val work: ItemKey,
    val rank: Int,
    val linked: Boolean,
    val updatedAt: Instant,
    val firstSeenAt: Instant,
    val values: RowValues,
    val facets: Map<String, List<String>>,
    val numbers: Map<String, Double>,
    val title: String,
    val model: Map<String, List<String>> = emptyMap(),
    val stages: WorkStages = WorkStages.NONE,
    val dates: Map<WorkDatum, Long> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val vectors: RowVectors? = null,
) {
    /**
     * The row with the [stages] of the tasks over its work and the dates of what they made of it ([kept], of the data
     * not outer — the outer ones as the databases date them now): as its document kept them.
     */
    fun with(stages: WorkStages, kept: Map<WorkDatum, Long>) = of(stages, dates + kept.filterKeys { !it.outer })

    /** The row with the [datum] dated [at]. */
    fun dated(datum: WorkDatum, at: Long) = of(stages, dates + (datum to at))

    /** The row with the [vectors] of its texts. */
    fun withVectors(vectors: RowVectors?) = of(stages, dates, vectors)

    private fun of(stages: WorkStages, dates: Map<WorkDatum, Long>, vectors: RowVectors? = this.vectors) =
        ListRow(
            key,
            work,
            rank,
            linked,
            updatedAt,
            firstSeenAt,
            values,
            facets,
            numbers,
            title,
            model,
            stages,
            dates,
            texts,
            vectors,
        )
}

/**
 * The vectors of an item's texts by the [encoder], as its document holds them ([ListTexts.packed]), by the texts' keys:
 * [packed] made as the document is.
 */
class RowVectors(val encoder: String, private val made: () -> Map<String, ByteArray>) {
    val packed: Map<String, ByteArray>
        get() = made()

    companion object {
        /** The vectors of an item of none by the [encoder]. */
        fun none(encoder: String) = RowVectors(encoder) { emptyMap() }

        /**
         * The vectors of the texts of the items [ids] by the [encoder] as their documents hold them, read by their
         * keys.
         */
        fun of(s: SourceStore, ids: Collection<String>, encoder: String): Map<String, RowVectors> =
            s.textVectors.halvesOf(ids, encoder, ListTexts.vectorKeys(s.schema)).mapValues { (_, own) ->
                val packed = own.mapValues { ListTexts.packedHalves(it.value) }
                RowVectors(encoder) { packed }
            }
    }
}

/**
 * THE ROWS OF THE LIST READ OF THE DATABASES, of one source: of the items named by their ids, a portion of what changed
 * at a time ([ListChanges]). What the index of the list is made of ([ListIndex]); nothing of it kept after the reading.
 */
internal object ListRows {

    /**
     * The rows of the items [ids] of the source [s], of the works [clusters] tells; [model]: of every item, by the
     * facet's key, the values the models give it beyond the site's own ([ListModelValues]); [predicted]: of every item,
     * its prediction as the index kept it ([ListPredictions]). The [stages] of every item — when the tasks over the
     * works did it —, none of an item not told. The vectors of their texts by the [encoder], none without one.
     */
    fun read(
        s: SourceStore,
        ids: Collection<String>,
        clusters: WorkClusters,
        model: Map<String, Map<String, List<String>>>,
        predicted: Map<String, Predicted>,
        encoder: String?,
        stages: (String) -> WorkStages = { WorkStages.NONE },
    ): List<ListRow> {
        val heads = s.items.heads(ids).values
        if (heads.isEmpty()) return emptyList()
        val ratings = s.ratings.ofItems(ids).groupBy { it.itemId }
        val marks = s.interest.ofItems(ids)
        val corrections = s.corrections.facetsOf(ids)
        val facets = facetsOf(s, ids, model, corrections)
        val fields = s.corrections.fieldsOf(ids)
        val numbers = numbersOf(s, ids, fields)
        val titles = heads.associate { it.id to Corrected.title(it.title, fields[it.id].orEmpty()) }
        val texts = textsOf(s, ids, fields)
        val vectors = if (encoder == null) emptyMap() else RowVectors.of(s, ids, encoder)
        val none = encoder?.let(RowVectors::none)
        // When every outer kind of data of each changed last: of its item, and of the user's word on its work
        val itemDates = s.changes.of(ids)
        val workDates = s.works.changes.ofItems(s.id, ids)
        return heads.map { h ->
            val key = ItemKey(s.id, h.id)
            val members = clusters.fullestFirst(key)
            ListRow(
                key = key,
                work = clusters.representative(key),
                rank = members.indexOf(key).coerceAtLeast(0),
                linked = clusters.linked(key),
                updatedAt = h.updatedAt,
                firstSeenAt = h.firstSeenAt,
                values =
                    RowValues.of(
                        h.version,
                        ratings[h.id],
                        predicted[h.id],
                        marks[h.id],
                        !corrections[h.id].isNullOrEmpty(),
                    ),
                facets = facets.mapValues { (_, byItem) -> byItem[h.id].orEmpty() },
                numbers = numbers[h.id].orEmpty(),
                title = titles[h.id].orEmpty(),
                model = model[h.id].orEmpty(),
                stages = stages(h.id),
                dates = itemDates[h.id].orEmpty() + workDates[h.id].orEmpty(),
                texts = texts[h.id].orEmpty(),
                vectors = vectors[h.id] ?: none,
            )
        }
    }

    /** The texts held whole ([ListTexts.textKeys]) of the items [ids] as the user corrected them ([fields]). */
    private fun textsOf(
        s: SourceStore,
        ids: Collection<String>,
        fields: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> {
        val keys = ListTexts.textKeys(s.schema)
        if (keys.isEmpty()) return emptyMap()
        val site = s.items.textsOf(ids, keys)
        val wanted = keys.toSet()
        val out = HashMap<String, Map<String, String>>()
        (site.keys + fields.keys).forEach { id ->
            val own = fields[id].orEmpty()
            val corrected =
                Corrected.texts(site[id].orEmpty(), own).filterKeys { it in wanted }.filterValues { it.isNotBlank() }
            if (corrected.isNotEmpty()) out[id] = corrected
        }
        return out
    }

    /** The ids of the filter facets of the source [s]: what the dictionaries draft of the site's words. */
    fun filterIds(s: SourceStore): Set<String> =
        s.schema.facets.filter { it.filter }.mapTo(HashSet()) { FeatureNames.facetId(s.source, it) }

    /**
     * The values of the items of the source that change without the item — the grade, prediction and interest —, of
     * each of them [predicted] so.
     */
    fun valuesOf(s: SourceStore, predicted: Map<String, Predicted>): Map<String, RowValues> {
        val ids = predicted.keys
        // Of many — the predictions of a training — every item's version read in one pass, not a thousand at a time
        val versions =
            if (ids.size > MANY)
                ids.toHashSet()
                    .let { asked -> s.items.keys().filter { it.id in asked } }
                    .associate { it.id to it.version }
            else s.items.heads(ids).mapValues { it.value.version }
        val ratings = s.ratings.ofItems(ids).groupBy { it.itemId }
        val marks = s.interest.ofItems(ids)
        val answered = s.corrections.facetsOf(ids).filterValues { it.isNotEmpty() }.keys
        return versions.mapValues { (id, version) ->
            RowValues.of(version, ratings[id], predicted[id], marks[id], id in answered)
        }
    }

    /**
     * Of every filter facet of the source, and of its authors, by its filter id, every item's values as the user sees
     * them: the site's, the drafts of the layer, the models' of a facet it works out ([model]: of every item, by the
     * facet's key, beyond the site's own), the user's corrections on top — what the index keeps of them: of the items
     * [ids]. The authors and the site's fandoms no filter, kept for the search of duplicates to read of the index, not
     * of the database (user, 2026-11-18; owner, 2026-11-22); the site's facets the tags are drafted of, for the changes
     * of the dictionaries to find their works by ([ListDictionary]).
     */
    fun facetsOf(
        s: SourceStore,
        ids: Collection<String>,
        model: Map<String, Map<String, List<String>>>,
        /** The user's answers on the facets of the items, read by the caller already; read now when not. */
        answers: Map<String, List<FacetCorrection>>? = null,
    ): Map<String, Map<String, List<String>>> {
        val fandoms = UniverseFacet.fandomFacet(s.schema)
        // The site's own facets the tags are drafted of kept too: what a change of the dictionaries finds the works by
        val drafting = s.schema.facets.mapNotNullTo(HashSet()) { it.examplesFrom }
        val defs =
            s.schema.facets.filter {
                it.filter || it.role == FacetRole.AUTHOR || it.key == fandoms || it.key in drafting
            }
        if (defs.isEmpty()) return emptyMap()
        val site = s.items.facetsOf(ids)
        val corrections = answers ?: s.corrections.facetsOf(ids)
        // The drafts of the layer — the site's words bound to the layer's values, a site's tag to a tag — given as the
        // model's are, before any model is fitted
        val drafts = HashMap<String, HashMap<String, MutableList<String>>>()
        WorkDrafts.of(s, ids, site).forEach { (id, own) ->
            own.forEach { d -> drafts.getOrPut(d.facet) { HashMap() }.getOrPut(id) { ArrayList() } += d.key }
        }
        return defs.associate { def ->
            val drafted = drafts[def.key].orEmpty()
            val byItem = HashMap<String, List<String>>()
            val modelled = if (def.infer) model.filterValues { def.key in it }.keys else emptySet()
            (site.keys + modelled + drafted.keys + corrections.keys).forEach { id ->
                val given = site[id]?.get(def.key).orEmpty() + drafted[id].orEmpty() + model[id]?.get(def.key).orEmpty()
                val shown = shown(def, given, corrections[id])
                if (shown.isNotEmpty()) byItem[id] = shown
            }
            FeatureNames.facetId(s.source, def) to byItem
        }
    }

    /**
     * The values of the facet [def] of an item, the site's and the model's [given], with the user's word [own] on top.
     */
    private fun shown(def: FacetDef, given: List<String>, own: List<FacetCorrection>?): List<String> {
        val mine = own.orEmpty().filter { it.facet == def.key }
        if (mine.isEmpty()) return given.distinct()
        val single = if (def.single) setOf(def.key) else emptySet()
        return Corrected.facets(mapOf(def.key to given.distinct()), mine, single)[def.key].orEmpty()
    }

    /**
     * Every number of the items [ids] by the number's id, the user's word on top; and the words and the characters of
     * an item's kept texts ([PartCounts.WORDS_ID], [PartCounts.CHARS_ID]), as they lie. The user's word of the [fields]
     * read of the items alone.
     */
    private fun numbersOf(
        s: SourceStore,
        ids: Collection<String>,
        fields: Map<String, Map<String, String>>,
    ): Map<String, Map<String, Double>> {
        val defs = s.schema.numbers.associateBy { it.key }
        val out = textSizesOf(s, ids)
        if (defs.isEmpty()) return out
        fun put(id: String, key: String, value: Double) {
            val def = defs[key] ?: return
            out.getOrPut(id) { HashMap() }[FeatureNames.numberId(s.source, def)] = value
        }
        s.items.numbersOf(ids).forEach { (id, numbers) -> numbers.forEach { (key, value) -> put(id, key, value) } }
        fields.forEach { (id, own) ->
            defs.keys.forEach { key -> own[Corrected.numberField(key)]?.toDoubleOrNull()?.let { put(id, key, it) } }
        }
        return out
    }

    /** The words and the characters of the kept texts of the items [ids], as they lie. */
    private fun textSizesOf(s: SourceStore, ids: Collection<String>): HashMap<String, HashMap<String, Double>> {
        val out = HashMap<String, HashMap<String, Double>>()
        s.parts.counts.counted(ids).forEach { (id, size) ->
            out[id] = hashMapOf(PartCounts.WORDS_ID to size.first, PartCounts.CHARS_ID to size.second)
        }
        s.parts.counts.classes(ids).forEach { (id, classes) ->
            val numbers = out.getOrPut(id) { HashMap() }
            classes.forEachIndexed { k, v -> numbers[PartCounts.classId(k)] = v.toDouble() }
        }
        return out
    }

    /** Of more items than so many, every item read and picked from. */
    internal const val MANY = 10_000
}
