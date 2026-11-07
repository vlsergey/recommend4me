package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.review.StoredReview
import io.github.vlsergey.recommend4me.setvector.SetEmbedding
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import kotlin.math.abs
import org.springframework.stereotype.Component

/** Everything the dialog of a work shows of the model. */
class Details(
    val explanation: List<ContributionGroup>,
    val reviews: List<Pair<StoredReview, Double?>>,
    val pictures: Map<Int, Double>,
    val pictureMatches: List<MarkLikeness.Match>,
    val reviewMatches: List<MarkLikeness.Match>,
    /** What every part whose text is kept moves the work by, in points: part to points. */
    val parts: Map<String, Double> = emptyMap(),
)

/**
 * WHAT MOVES THE PREDICTION OF A WORK, as the user is shown it: the parts of its input switched off one by one; its
 * pictures, its reviews and the parts of its text left out one at a time; the things the user marked on other works its
 * own are alike.
 */
@Component
class Explanations(
    private val stores: Stores,
    private val plugins: Plugins,
    private val kept: KeptModels,
    private val scoring: Scoring,
    private val likenesses: Likenesses,
    private val phrases: PhraseVectors,
) {

    /**
     * What moves the prediction of the work, part by part, strongest first, by the model of every grade — for a page of
     * the site. Of a graded work the page shows the model's that had not seen it ([HeldOutModels.heldOut]): the model
     * of every grade explains it by what it learnt of the work itself.
     */
    fun explanation(key: ItemKey): List<ContributionGroup> {
        val type = stores.typeOf(key.source)
        val model = kept.load(type)
        val input = model?.let { scoring.inputOf(type, key, likenesses.stored(type)) }
        return if (model == null || input == null) emptyList() else explain(model, input, type)
    }

    fun details(key: ItemKey): Details {
        val type = stores.typeOf(key.source)
        val store = type.source(key.source)!!
        val stored = likenesses.stored(type)
        val model = kept.load(type)
        val reviews = store.reviews.ofItem(key.id)
        val input = model?.let { scoring.inputOf(type, key, stored) }
        return when {
            model == null ->
                Details(
                    emptyList(),
                    reviews.take(REVIEWS_SHOWN).map { it to null },
                    emptyMap(),
                    emptyList(),
                    emptyList(),
                )
            input == null -> Details(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
            else ->
                Details(
                    explain(model, input, type),
                    reviewInfluence(type, key, model, input, stored, reviews)
                        ?: reviews.take(REVIEWS_SHOWN).map { it to null },
                    pictureInfluence(type, key, model, input, stored),
                    pictureMatches(type, key, stored),
                    reviewMatches(type, key, stored, reviews),
                    partInfluence(type, key, model, input),
                )
        }
    }

    /**
     * What moves the work's score, in points, PART BY PART — a facet with all its values, the pictures, a text, a
     * number, the user's own marks on the site — each with every feature in it; the strongest first, nothing left out.
     * A part is switched off as a whole: what it adds together is not the sum of what its features add one by one.
     *
     * Switched off, a vector or a number is the average; a categorical feature is put at how often the catalogue has it
     * — so a liked tag the work lacks counts too, by what lacking it costs against the average work.
     */
    fun explain(model: TrainedModel, input: ItemInput, type: TypeStore): List<ContributionGroup> {
        val layout = model.layout
        val dense = layout.groups(input).filter { it !is FeatureGroup.Categorical }
        val categorical = layout.categorical.filter { it in input.categorical || !it.startsWith("prev:") }
        val parts = (dense.map { it.feature } + categorical).groupBy(::partOf)
        val denseOf = dense.associateBy { it.feature }
        val share = model.meta.categoricalShare
        val m = Matrix(1 + dense.size + categorical.size + parts.size, layout.width)
        fun average(row: Int, names: Collection<String>) = names.forEach { name ->
            val i = layout.categorical.indexOf(name)
            if (i >= 0) m.held[m.row(row) + layout.categoricalOffset + i] = share.getOrElse(i) { 0f }
        }
        layout.write(input, m, 0)
        dense.forEachIndexed { g, group -> layout.write(input, m, g + 1, group) }
        categorical.forEachIndexed { c, name ->
            val row = 1 + dense.size + c
            layout.write(input, m, row)
            average(row, listOf(name))
        }
        val partList = parts.entries.toList()
        partList.forEachIndexed { p, (_, members) ->
            val row = 1 + dense.size + categorical.size + p
            layout.write(input, m, row, members.mapNotNull { denseOf[it] })
            average(row, members.filter { it !in denseOf })
        }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        fun moved(row: Int) = full - model.scale.score(raw[row].toDouble())
        val features =
            dense.mapIndexed { g, group -> group.feature to moved(g + 1) }.toMap() +
                categorical.mapIndexed { c, name -> name to moved(1 + dense.size + c) }
        val labels = FeatureNames.labels(features.keys, type.sources)
        val partLabels = FeatureNames.partLabels(parts.keys, type.sources)
        return partList
            .mapIndexed { p, (part, members) ->
                ContributionGroup(
                    part,
                    partLabels.getValue(part),
                    moved(1 + dense.size + categorical.size + p),
                    members
                        .map {
                            LabelledContribution(
                                Contribution(it, features.getValue(it), it in input.categorical || it in denseOf),
                                labels.getValue(it),
                            )
                        }
                        .sortedByDescending { abs(it.contribution.contribution) },
                )
            }
            .sortedByDescending { abs(it.contribution) }
    }

    /**
     * What every analysed picture of the work moves it by, in points: the score as it is minus the score with that one
     * picture left out — the cover dropped, or a screenshot taken out of the screenshots' mean, their set and their
     * likeness to the marks.
     */
    private fun pictureInfluence(
        type: TypeStore,
        key: ItemKey,
        model: TrainedModel,
        input: ItemInput,
        stored: TypeLikeness,
    ): Map<Int, Double> {
        val encoder = plugins.imageEncoder()
        val pictures = encoder?.let { type.source(key.source)!!.pictures.analysed.positioned(key.id, it.id) }.orEmpty()
        if (pictures.isEmpty()) return emptyMap()
        val screenSet = type.embeddings.find(SetKind.SCREENS)?.takeIf { it.encoder == encoder!!.id }?.embedding
        val others =
            input.vectors -
                PictureRepository.COVER -
                PictureRepository.SCREENS -
                SetKind.SCREENS.key -
                MarkLikeness.PICTURES
        fun inputWith(list: List<Pair<Int, FloatArray>>): ItemInput {
            val set = screenSet?.of(list.filter { it.first > 0 }.map { it.second })
            val marks =
                stored.pictures.rowOf(key, list.map { (p, v) -> p.toString() to v })?.let { row ->
                    stored.pictures.vector(row) { it.owner == key }
                }
            return input.withVectors(
                others +
                    PictureRepository.combine(list) +
                    listOfNotNull(set?.let { SetKind.SCREENS.key to it }, marks?.let { MarkLikeness.PICTURES to it })
            )
        }
        val inputs =
            listOf(inputWith(pictures)) +
                pictures.indices.map { i -> inputWith(pictures.filterIndexed { j, _ -> j != i }) }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return pictures.indices.associate { i -> pictures[i].first to full - model.scale.score(raw[i + 1].toDouble()) }
    }

    /**
     * What every review of the work moves it by, in points: the score as it is minus the score with that review left
     * out of the reviews' set and of their likeness to the marked ones. The strongest either way, strongest first; null
     * when the model reads no reviews.
     */
    private fun reviewInfluence(
        type: TypeStore,
        key: ItemKey,
        model: TrainedModel,
        input: ItemInput,
        stored: TypeLikeness,
        list: List<StoredReview>,
    ): List<Pair<StoredReview, Double?>>? {
        val embedding =
            plugins.textEncoder()?.let { encoder ->
                type.embeddings.find(SetKind.REVIEWS)?.takeIf { it.encoder == encoder.id }?.embedding
            }
        return when {
            embedding == null -> null
            list.isEmpty() -> emptyList()
            else -> reviewsMoved(type, key, model, input, stored, list, embedding)
        }
    }

    /**
     * What every one of the reviews [list] of the work moves it by, the model reading them by the set [embedding]; the
     * strongest either way, strongest first.
     */
    private fun reviewsMoved(
        type: TypeStore,
        key: ItemKey,
        model: TrainedModel,
        input: ItemInput,
        stored: TypeLikeness,
        list: List<StoredReview>,
        embedding: SetEmbedding,
    ): List<Pair<StoredReview, Double?>> {
        val store = type.source(key.source)!!
        val texts = list.map { ReviewRepository.textOf(it.content) }
        val unique = texts.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) return list.take(REVIEWS_SHOWN).map { it to null }
        val index = unique.withIndex().associate { (i, t) -> t to i }
        val owners = IntArray(unique.size)
        texts.forEach { t -> index[t]?.let { owners[it]++ } }
        val encoded = phrases.of(store, unique)
        val projections = embedding.project(unique.map { encoded.getValue(it) })
        val others = input.vectors - SetKind.REVIEWS.key - MarkLikeness.REVIEWS
        val items = list.indices.mapNotNull { i -> encoded[texts[i]]?.let { list[i].reviewId to it } }
        fun marks(without: String?) =
            stored.reviews.rowOf(key, items.filter { it.first != without })?.let { row ->
                stored.reviews.vector(row) { it.owner == key }
            }
        fun inputWith(set: FloatArray?, marks: FloatArray?) =
            input.withVectors(
                others +
                    listOfNotNull(set?.let { SetKind.REVIEWS.key to it }, marks?.let { MarkLikeness.REVIEWS to it })
            )
        val inputs =
            listOf(inputWith(embedding.quantiles(projections, null), marks(null))) +
                list.indices.map { i ->
                    val keep = BooleanArray(unique.size) { true }
                    // A review word for word the same as another stays, as the other
                    index[texts[i]]?.let { j -> if (owners[j] == 1) keep[j] = false }
                    inputWith(embedding.quantiles(projections, keep), marks(list[i].reviewId))
                }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return list.indices
            .map { i -> list[i] to (full - model.scale.score(raw[i + 1].toDouble())) as Double? }
            .sortedByDescending { abs(it.second ?: 0.0) }
            .take(REVIEWS_SHOWN)
    }

    /**
     * What every part of the work — a chapter whose text is kept — moves it by, in points: the score as it is minus the
     * score with that part's windows left out of the set of the text's windows. None when the model reads no text of
     * parts.
     */
    private fun partInfluence(
        type: TypeStore,
        key: ItemKey,
        model: TrainedModel,
        input: ItemInput,
    ): Map<String, Double> {
        val encoder = plugins.textEncoder()?.takeIf { model.layout.offsetOf(SetKind.PARTS.key) != null }
        val embedding = encoder?.let { e ->
            type.embeddings.find(SetKind.PARTS)?.takeIf { it.encoder == e.id }?.embedding
        }
        val byPart =
            if (embedding == null) emptyMap()
            else type.source(key.source)!!.parts.windows.windowsByPart(key.id, encoder.id)
        if (embedding == null || byPart.isEmpty()) return emptyMap()
        val parts = byPart.keys.toList()
        val windows = parts.flatMap { byPart.getValue(it) }
        val owners = parts.flatMap { id -> List(byPart.getValue(id).size) { id } }
        val projections = embedding.project(windows)
        val others = input.vectors - SetKind.PARTS.key
        fun inputWith(set: FloatArray?) =
            input.withVectors(others + listOfNotNull(set?.let { SetKind.PARTS.key to it }))
        val inputs =
            listOf(inputWith(embedding.quantiles(projections, null))) +
                parts.map { id ->
                    inputWith(embedding.quantiles(projections, BooleanArray(windows.size) { owners[it] != id }))
                }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return parts.withIndex().associate { (i, id) -> id to full - model.scale.score(raw[i + 1].toDouble()) }
    }

    /** The pictures of other works marked by the user that the work's pictures are most alike, strongest first. */
    private fun pictureMatches(type: TypeStore, key: ItemKey, stored: TypeLikeness): List<MarkLikeness.Match> {
        val row =
            plugins.imageEncoder()?.let { encoder ->
                stored.pictures.rowOf(
                    key,
                    type.source(key.source)!!.pictures.analysed.positioned(key.id, encoder.id).map { (p, v) ->
                        p.toString() to v
                    },
                )
            }
        return if (row == null) emptyList() else stored.pictures.matches(key, row, MATCHES_SHOWN)
    }

    /** The reviews of other works marked by the user that the work's reviews read most alike, strongest first. */
    private fun reviewMatches(
        type: TypeStore,
        key: ItemKey,
        stored: TypeLikeness,
        reviews: List<StoredReview>,
    ): List<MarkLikeness.Match> {
        if (stored.reviews.marks.isEmpty()) return emptyList()
        val store = type.source(key.source)!!
        val texts = reviews.map { it.reviewId to ReviewRepository.textOf(it.content) }.filter { it.second.isNotEmpty() }
        val vectors = phrases.of(store, texts.map { it.second })
        val row = stored.reviews.rowOf(key, texts.mapNotNull { (id, text) -> vectors[text]?.let { id to it } })
        return if (row == null) emptyList() else stored.reviews.matches(key, row, MATCHES_SHOWN)
    }

    private companion object {
        const val REVIEWS_SHOWN = 30
        const val MATCHES_SHOWN = 3
    }
}

/** The blocks of the pictures: an explanation tells of them together. */
private val PICTURE_BLOCKS =
    setOf(PictureRepository.COVER, PictureRepository.SCREENS, SetKind.SCREENS.key, MarkLikeness.PICTURES)

/** The blocks of the opinions of other people. */
private val REVIEW_BLOCKS = setOf(SetKind.REVIEWS.key, MarkLikeness.REVIEWS)

/**
 * The part of the input a feature is switched off with: its facet, the pictures, the reviews, the text; else itself.
 */
internal fun partOf(feature: String): String =
    FeatureNames.parseFacet(feature)?.let { "facet:${it.first}" }
        ?: when {
            feature.startsWith("prev:") -> FeatureNames.PREVIOUS
            feature.startsWith("source:") -> FeatureNames.SOURCE
            feature in PICTURE_BLOCKS -> FeatureNames.PICTURES
            feature in REVIEW_BLOCKS -> FeatureNames.REVIEWS
            else -> feature
        }
