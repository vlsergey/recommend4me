package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.review.StoredReview
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.setvector.SetVectorsChanged
import io.github.vlsergey.recommend4me.signal.SignalsChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

private val log = LoggerFactory.getLogger(Recommendations::class.java)

/** A trained model as kept: its description and its scorer, read for one operation. */
class TrainedModel(val meta: ModelMeta, val scorer: FittedScorer) {
    val layout = meta.layout()
    val scale = meta.scale()

    fun scores(m: Matrix): FloatArray = scorer.scores(m)

    fun score(input: ItemInput): Double = scale.score(scorer.scores(layout.matrix(listOf(input)))[0].toDouble())
}

/**
 * Trains the model of every content type on the user's grades and keeps a score of 0..10 for every
 * item. Training is cheap enough — a few thousand ratings of a few thousand numbers — to be redone
 * from scratch after the grades change and after every sync rather than updated incrementally.
 *
 * NOTHING IS KEPT IN MEMORY between operations: the model is read from its database when it is
 * needed, the catalogue loaded at the start of a training and dropped at its end.
 */
@Service
class Recommendations(
    private val stores: Stores,
    private val plugins: Plugins,
    private val reader: CatalogueReader,
    private val likenesses: Likenesses,
    private val phrases: PhraseVectors,
    private val settings: Settings,
    private val json: JsonMapper,
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val trainingNow = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "model-training").apply { isDaemon = true } }

    private fun lock(type: String) = locks.computeIfAbsent(type) { ReentrantLock() }

    private fun type(id: String): TypeStore = stores.type(id) ?: error("No such content type: $id")

    /** Training is running or scheduled after a recent change. */
    fun training(type: String): Boolean = type in trainingNow || pending[type]?.isDone == false

    /** Retrains shortly after the last of a quick series of changes. */
    fun scheduleRetrain(type: String) {
        pending.compute(type) { _, old ->
            old?.cancel(false)
            scheduler.schedule({
                try {
                    retrainAndScore(type)
                } catch (e: Exception) {
                    log.error("{}: background training failed", type, e)
                }
            }, RETRAIN_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    @EventListener
    fun gradesChanged(event: GradesChanged) {
        settings.set("model.${event.type}.gradesChangedAt", System.currentTimeMillis().toString())
        scheduleRetrain(event.type)
    }

    @EventListener
    fun marksChanged(event: MarksChanged) = scheduleRetrain(event.type)

    @EventListener
    fun signalsChanged(event: SignalsChanged) = scheduleRetrain(event.type)

    @EventListener
    fun setVectorsChanged(event: SetVectorsChanged) {
        log.info("{}: {} items have new set vectors: retraining", event.type, event.items)
        scheduleRetrain(event.type)
    }

    /** A model that could not be read, or none, is trained on start where there are grades. */
    @EventListener(ApplicationReadyEvent::class)
    fun trainIfMissing() {
        stores.types.forEach { t ->
            if (load(t) == null && t.sources.any { it.ratings.count() > 0 }) scheduleRetrain(t.id)
        }
    }

    fun retrainAndScore(typeId: String): ModelSummary = lock(typeId).withLock {
        val type = type(typeId)
        trainingNow += typeId
        try {
            val started = System.currentTimeMillis()
            val catalogue = reader.read(type)
            val loaded = System.currentTimeMillis()
            val model = retrain(catalogue)
            val trained = System.currentTimeMillis()
            if (model != null) scoreAll(catalogue, model)
            log.info(
                "{}: retrained and every item scored in {} ms: read {}, trained {}, scored {}",
                typeId, System.currentTimeMillis() - started, loaded - started, trained - loaded, System.currentTimeMillis() - trained,
            )
        } finally {
            trainingNow -= typeId
        }
        summary(typeId)
    }

    /** Re-scores every item of the type with its current model. */
    fun scoreAll(typeId: String) = lock(typeId).withLock {
        val type = type(typeId)
        val model = load(type) ?: return@withLock
        scoreAll(reader.read(type, withLikeness = false), model)
    }

    /** The current model of a type, read now; null without one, or when it cannot be read. */
    fun load(type: TypeStore): TrainedModel? = try {
        type.models.load()?.let { stored ->
            val scorer = plugins.scorers.firstOrNull { it.id == stored.scorer } ?: return null
            TrainedModel(json.readValue<ModelMeta>(stored.meta), scorer.unpack(stored.weights))
        }
    } catch (e: Exception) {
        log.warn("{}: the stored model cannot be read and will be retrained: {}", type.id, e.message)
        null
    }

    // --- Training ---

    /** The graded items as the model sees them; null when there are too few grades to train on. */
    private class Prepared(
        val used: List<TypedRating>,
        val grades: IntArray,
        val works: LongArray,
        val perItem: List<ItemInput>,
        val layout: FeatureLayout,
        val x: Matrix,
        val inputs: List<ItemInput>,
        val spreads: Map<String, VectorSpread>,
        val numbers: Map<String, NumberSpread>,
        /** The rows as a fold sees them: the marks of the works under test not counted; null when nothing depends on them. */
        val xFor: ((Set<Long>) -> Matrix)?,
    ) {
        val everyRow = IntArray(used.size) { it }
        val task = RankingTask(x, grades, works)
    }

    private fun prepare(catalogue: Catalogue): Prepared? {
        val likeness = catalogue.likeness
        val previousForRating = CatalogueReader.previousGradesOfRatings(catalogue.ratings)
        val previousForCurrent = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        val used = catalogue.ratings.filter { it.key in catalogue.byKey }
        val grades = IntArray(used.size) { used[it].grade }
        if (used.size < MIN_RATINGS || grades.distinct().size < 2) {
            log.info("{}: not enough grades to train: {} of {} values", catalogue.type.id, used.size, grades.distinct().size)
            return null
        }
        val inputs = used.map { r ->
            CatalogueReader.inputOf(catalogue.byKey.getValue(r.key), catalogue.vectorsOf(r.key), previousForRating[r])
        }
        // The current input of every graded item: what the vocabulary and the importances are counted on
        val perItem = used.map { it.key }.distinct().map { key ->
            CatalogueReader.inputOf(catalogue.byKey.getValue(key), catalogue.vectorsOf(key), previousForCurrent[key])
        }
        val spreads = VectorSpread.of { action ->
            catalogue.vectors.values.forEach { blocks -> blocks.forEach { (key, v) -> action(key, v) } }
            likeness.pictures.forEach { _, v -> action(MarkLikeness.PICTURES, v) }
            likeness.reviews.forEach { _, v -> action(MarkLikeness.REVIEWS, v) }
        }
        val numbers = FeatureLayout.numberSpreads(catalogue.items.map { ItemInput(it.key, emptyMap(), emptySet(), it.numeric) })
        val layout = FeatureLayout.of(perItem, spreads, numbers)
        val x = layout.matrix(inputs)
        val works = LongArray(used.size) { catalogue.works.work(used[it].key) }
        val keysOfWork = used.indices.groupBy({ works[it] }, { used[it].key })
        // The likeness of a graded item to the marks of the works under test would tell the fold what it is tested on
        val ofFold = HashMap<Set<Long>, Matrix>()
        val xFor = if (likeness.empty) null else { underTest: Set<Long> ->
            synchronized(ofFold) {
                ofFold.getOrPut(underTest) {
                    val excluded = underTest.flatMap { keysOfWork[it].orEmpty() }.toSet()
                    layout.matrix(inputs.map { input ->
                        input.withVectors(input.vectors - MARK_KEYS + likeness.vectors(input.key, excluded))
                    })
                }
            }
        }
        return Prepared(used, grades, works, perItem, layout, x, inputs, spreads, numbers, xFor)
    }

    /** The scorer fitted on all grades with [parameter], and its scale: the ladder from [measured]'s out-of-fold scores. */
    private fun scaled(scorer: Scorer, p: Prepared, parameter: Double, measured: RankingCandidate?): Pair<FittedScorer, Scale> {
        val fitted = scorer.fit(p.task, p.everyRow, parameter)
        val own = fitted.scores(p.x).map { it.toDouble() }
        val (mean, sd) = CrossValidation.standardisation(own)
        // Too few works for folds: the ladder of the training scores, too wide but the only one there is
        val scores = measured?.scores ?: DoubleArray(own.size) { (own[it] - mean) / sd }
        return fitted to Scale(mean, sd, CrossValidation.ladder(p.grades, scores))
    }

    private fun retrain(catalogue: Catalogue): TrainedModel? {
        val type = catalogue.type
        val scorer = plugins.scorer(type.id) ?: run {
            log.warn("{}: no scorer plugin: the works are not ranked", type.id)
            return null
        }
        val p = prepare(catalogue)
        if (p == null) {
            type.models.forget()
            return null
        }
        val started = System.currentTimeMillis()
        val candidates = if (p.perItem.size >= CrossValidation.MIN_WORKS) CrossValidation.measureAll(p.task, scorer, p.xFor) else emptyList()
        val chosen = CrossValidation.choose(candidates)
        val parameter = chosen?.parameter ?: scorer.defaultParameter
        log.info(
            "{}: {} values of the {} parameter measured on {} grades × {} features in {} ms, chosen {}",
            type.id, candidates.size, scorer.id, p.used.size, p.layout.width, System.currentTimeMillis() - started, parameter,
        )
        val (fitted, scale) = scaled(scorer, p, parameter, chosen)
        val share = categoricalShare(p.layout, catalogue)
        val provisional = ModelMeta(
            scorer = scorer.id, parameter = parameter, categorical = p.layout.categorical, categoricalShare = share,
            vectorCenters = p.spreads.mapValues { it.value.center }, vectorSpreads = p.spreads.mapValues { it.value.spread },
            numbers = p.numbers, scoreMean = scale.mean, scoreSd = scale.sd, ladder = scale.ladder,
            trainedAtMillis = System.currentTimeMillis(), ratingCounts = counts(p.grades),
            metrics = chosen?.metrics, candidates = candidates.map { it.result() },
            topPositive = emptyList(), topNegative = emptyList(),
            textEncoder = plugins.textEncoder()?.id, imageEncoder = plugins.imageEncoder()?.id,
        )
        val model = TrainedModel(provisional, fitted)
        val importance = importance(model, p.perItem, type)
        val meta = provisional.copy(
            topPositive = importance.filter { it.contribution > 0 }.take(TOP_FEATURES),
            topNegative = importance.filter { it.contribution < 0 }.reversed().take(TOP_FEATURES),
        )
        type.models.save(scorer.id, Instant.ofEpochMilli(meta.trainedAtMillis), json.writeValueAsString(meta), fitted.pack())
        return TrainedModel(meta, fitted)
    }

    private fun scoreAll(catalogue: Catalogue, model: TrainedModel) {
        val previousForCurrent = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
        val scores = catalogue.items.chunked(SCORE_CHUNK).parallelStream().flatMap { chunk ->
            val inputs = chunk.map { CatalogueReader.inputOf(it, catalogue.vectorsOf(it.key), previousForCurrent[it.key]) }
            val raw = model.scores(model.layout.matrix(inputs))
            chunk.indices.map { i -> chunk[i].key to model.scale.score(raw[i].toDouble()) }.stream()
        }.toList()
        val written = System.currentTimeMillis()
        catalogue.type.models.replacePredictions(scores)
        log.info("{}: {} predictions written in {} ms", catalogue.type.id, scores.size, System.currentTimeMillis() - written)
    }

    /** Re-scores one item, after its data changed outside a training. */
    fun scoreItem(key: ItemKey) {
        val type = stores.typeOf(key.source)
        val model = load(type) ?: return
        val input = inputOf(type, key, likenesses.stored(type)) ?: return
        type.models.savePrediction(key, model.score(input))
    }

    /** The input of one item as the model reads it now. */
    private fun inputOf(type: TypeStore, key: ItemKey, stored: TypeLikeness, vectors: Map<String, FloatArray>? = null): ItemInput? {
        val item = reader.item(type, key) ?: return null
        val ratings = type.source(key.source)!!.ratings.ofItem(key.id).map { TypedRating(key, it.version, it.grade, it.ratedAt) }
        val previous = CatalogueReader.previousGradesOfCurrentVersions(ratings) { item.version }[key]
        return CatalogueReader.inputOf(item, vectors ?: (reader.vectors(type, key) + likenesses.of(type, key, stored)), previous)
    }

    // --- What the interface shows ---

    /** Everything the dialog of a work shows of the model. */
    class Details(
        val explanation: List<Contribution>,
        val reviews: List<Pair<StoredReview, Double?>>,
        val pictures: Map<Int, Double>,
        val pictureMatches: List<MarkLikeness.Match>,
        val reviewMatches: List<MarkLikeness.Match>,
    )

    fun details(key: ItemKey): Details {
        val type = stores.typeOf(key.source)
        val store = type.source(key.source)!!
        val stored = likenesses.stored(type)
        val model = load(type)
        val reviews = store.reviews.ofItem(key.id)
        if (model == null) {
            return Details(emptyList(), reviews.take(REVIEWS_SHOWN).map { it to null }, emptyMap(), emptyList(), emptyList())
        }
        val input = inputOf(type, key, stored) ?: return Details(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        val influence = reviewInfluence(type, key, model, input, stored, reviews)
        return Details(
            explain(model, input, type),
            influence ?: reviews.take(REVIEWS_SHOWN).map { it to null },
            pictureInfluence(type, key, model, input, stored),
            pictureMatches(type, key, stored),
            reviewMatches(type, key, stored, reviews),
        )
    }

    /**
     * The features that move the work's score most, strongest first, in points. The vectors and
     * numbers are switched off one by one (switched off is the average); a categorical feature is
     * put at how often the catalogue has it — so a liked tag the work lacks counts too, by what
     * lacking it costs against the average work.
     */
    private fun explain(model: TrainedModel, input: ItemInput, type: TypeStore, limit: Int = 16): List<Contribution> {
        val layout = model.layout
        val dense = layout.groups(input).filter { it !is FeatureGroup.Categorical }
        val categorical = layout.categorical.filter { it in input.categorical || !it.startsWith("prev:") }
        val m = Matrix(1 + dense.size + categorical.size, layout.width)
        layout.write(input, m, 0)
        dense.forEachIndexed { g, group -> layout.write(input, m, g + 1, group) }
        val share = model.meta.categoricalShare
        categorical.forEachIndexed { c, name ->
            val row = 1 + dense.size + c
            layout.write(input, m, row)
            val i = layout.categorical.indexOf(name)
            m.held[m.row(row) + layout.categoricalOffset + i] = share.getOrElse(i) { 0f }
        }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        val contributions = dense.mapIndexed { g, group -> group.feature to (full - model.scale.score(raw[g + 1].toDouble())) to true } +
            categorical.mapIndexed { c, name -> name to (full - model.scale.score(raw[1 + dense.size + c].toDouble())) to (name in input.categorical) }
        val top = contributions.sortedByDescending { abs(it.first.second) }.take(limit)
        val labels = FeatureNames.labels(top.map { it.first.first }, type.sources)
        return top.map { (fc, present) -> Contribution(fc.first, labels.getValue(fc.first), fc.second, present) }
    }

    /** What a group of features adds to a work's score: the score with it minus the score with it switched off, in points. */
    private fun occlusion(model: TrainedModel, input: ItemInput): List<Pair<String, Double>> {
        val groups = model.layout.groups(input)
        val m = Matrix(groups.size + 1, model.layout.width)
        model.layout.write(input, m, 0)
        groups.forEachIndexed { g, group -> model.layout.write(input, m, g + 1, group) }
        val raw = model.scores(m)
        val full = model.scale.score(raw[0].toDouble())
        return groups.mapIndexed { g, group -> group.feature to full - model.scale.score(raw[g + 1].toDouble()) }
    }

    /** Mean contribution of every feature over the graded works that have it, strongest positive first. */
    private fun importance(model: TrainedModel, perItem: List<ItemInput>, type: TypeStore): List<Contribution> {
        val sums = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        perItem.take(MAX_IMPORTANCE_ITEMS).forEach { input ->
            occlusion(model, input).forEach { (feature, c) ->
                sums.merge(feature, c, Double::plus)
                counts.merge(feature, 1, Int::plus)
            }
        }
        val means = sums.entries.filter { (counts[it.key] ?: 0) >= FeatureLayout.MIN_SUPPORT }.map { it.key to it.value / counts.getValue(it.key) }
        val ranked = means.sortedByDescending { it.second }
        val shown = (ranked.take(TOP_FEATURES) + ranked.takeLast(TOP_FEATURES)).map { it.first }.distinct()
        val labels = FeatureNames.labels(shown, type.sources)
        return ranked.filter { it.first in labels }.map { (f, c) -> Contribution(f, labels.getValue(f), c) }
    }

    /**
     * What every analysed picture of the work moves it by, in points: the score as it is minus the
     * score with that one picture left out — the cover dropped, or a screenshot taken out of the
     * screenshots' mean, their set and their likeness to the marks.
     */
    private fun pictureInfluence(type: TypeStore, key: ItemKey, model: TrainedModel, input: ItemInput, stored: TypeLikeness): Map<Int, Double> {
        val encoder = plugins.imageEncoder() ?: return emptyMap()
        val pictures = type.source(key.source)!!.pictures.positioned(key.id, encoder.id)
        if (pictures.isEmpty()) return emptyMap()
        val screenSet = type.embeddings.find(SetKind.SCREENS)?.takeIf { it.encoder == encoder.id }?.embedding
        val others = input.vectors - PictureRepository.COVER - PictureRepository.SCREENS - SetKind.SCREENS.key - MarkLikeness.PICTURES
        fun inputWith(list: List<Pair<Int, FloatArray>>): ItemInput {
            val set = screenSet?.of(list.filter { it.first > 0 }.map { it.second })
            val marks = stored.pictures.rowOf(key, list.map { (p, v) -> p.toString() to v })?.let { row -> stored.pictures.vector(row) { it.owner == key } }
            return input.withVectors(
                others + PictureRepository.combine(list) +
                    listOfNotNull(set?.let { SetKind.SCREENS.key to it }, marks?.let { MarkLikeness.PICTURES to it }),
            )
        }
        val inputs = listOf(inputWith(pictures)) + pictures.indices.map { i -> inputWith(pictures.filterIndexed { j, _ -> j != i }) }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return pictures.indices.associate { i -> pictures[i].first to full - model.scale.score(raw[i + 1].toDouble()) }
    }

    /**
     * What every review of the work moves it by, in points: the score as it is minus the score with
     * that review left out of the reviews' set and of their likeness to the marked ones. The
     * strongest either way, strongest first; null when the model reads no reviews.
     */
    private fun reviewInfluence(
        type: TypeStore, key: ItemKey, model: TrainedModel, input: ItemInput, stored: TypeLikeness, list: List<StoredReview>,
    ): List<Pair<StoredReview, Double?>>? {
        val encoder = plugins.textEncoder() ?: return null
        val embedding = type.embeddings.find(SetKind.REVIEWS)?.takeIf { it.encoder == encoder.id }?.embedding ?: return null
        if (list.isEmpty()) return emptyList()
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
            stored.reviews.rowOf(key, items.filter { it.first != without })?.let { row -> stored.reviews.vector(row) { it.owner == key } }
        fun inputWith(set: FloatArray?, marks: FloatArray?) =
            input.withVectors(others + listOfNotNull(set?.let { SetKind.REVIEWS.key to it }, marks?.let { MarkLikeness.REVIEWS to it }))
        val inputs = listOf(inputWith(embedding.quantiles(projections, null), marks(null))) + list.indices.map { i ->
            val keep = BooleanArray(unique.size) { true }
            // A review word for word the same as another stays, as the other
            index[texts[i]]?.let { j -> if (owners[j] == 1) keep[j] = false }
            inputWith(embedding.quantiles(projections, keep), marks(list[i].reviewId))
        }
        val raw = model.scores(model.layout.matrix(inputs))
        val full = model.scale.score(raw[0].toDouble())
        return list.indices.map { i -> list[i] to (full - model.scale.score(raw[i + 1].toDouble())) as Double? }
            .sortedByDescending { abs(it.second ?: 0.0) }
            .take(REVIEWS_SHOWN)
    }

    /** The pictures of other works marked by the user that the work's pictures are most alike, strongest first. */
    private fun pictureMatches(type: TypeStore, key: ItemKey, stored: TypeLikeness): List<MarkLikeness.Match> {
        val encoder = plugins.imageEncoder() ?: return emptyList()
        val row = stored.pictures.rowOf(key, type.source(key.source)!!.pictures.positioned(key.id, encoder.id).map { (p, v) -> p.toString() to v })
            ?: return emptyList()
        return stored.pictures.matches(key, row, MATCHES_SHOWN)
    }

    /** The reviews of other works marked by the user that the work's reviews read most alike, strongest first. */
    private fun reviewMatches(type: TypeStore, key: ItemKey, stored: TypeLikeness, reviews: List<StoredReview>): List<MarkLikeness.Match> {
        if (stored.reviews.marks.isEmpty()) return emptyList()
        val store = type.source(key.source)!!
        val texts = reviews.map { it.reviewId to ReviewRepository.textOf(it.content) }.filter { it.second.isNotEmpty() }
        val vectors = phrases.of(store, texts.map { it.second })
        val row = stored.reviews.rowOf(key, texts.mapNotNull { (id, text) -> vectors[text]?.let { id to it } }) ?: return emptyList()
        return stored.reviews.matches(key, row, MATCHES_SHOWN)
    }

    /** Share of the catalogue's items with every categorical feature of the [layout]. */
    private fun categoricalShare(layout: FeatureLayout, catalogue: Catalogue): FloatArray {
        val index = layout.categorical.withIndex().associate { (i, name) -> name to i }
        val counts = IntArray(layout.categorical.size)
        catalogue.items.forEach { item -> item.categorical.forEach { name -> index[name]?.let { counts[it]++ } } }
        val total = catalogue.items.size
        return FloatArray(counts.size) { if (total > 0) counts[it].toFloat() / total else 0f }
    }

    fun summary(typeId: String): ModelSummary {
        val type = type(typeId)
        val model = load(type)
        val changedAt = settings.get("model.$typeId.gradesChangedAt")?.toLongOrNull() ?: 0L
        val counts = counts(type.sources.flatMap { s -> s.ratings.all().map { it.grade } }.toIntArray())
        return ModelSummary(
            meta = model?.meta,
            training = training(typeId),
            stale = if (model == null) counts.sum() > 0 else changedAt > model.meta.trainedAtMillis,
            textEncoderReady = plugins.textEncoder() != null,
            imageEncoderReady = plugins.imageEncoder() != null,
            scorer = plugins.scorer(typeId)?.id,
            ratingCounts = counts,
        )
    }

    /** Every scorer measured by cross-validation on the current grades; nothing is kept. */
    fun compare(typeId: String): List<ScorerQuality> = lock(typeId).withLock {
        val catalogue = reader.read(type(typeId))
        val p = prepare(catalogue) ?: return@withLock emptyList()
        plugins.scorers.map { scorer ->
            val candidates = CrossValidation.measureAll(p.task, scorer, p.xFor)
            ScorerQuality(scorer.id, scorer.title, CrossValidation.choose(candidates)?.result(), candidates.map { it.result() })
        }
    }

    /** Makes [scorerId] the scorer of the type and trains it. */
    fun chooseScorer(typeId: String, scorerId: String): ModelSummary? {
        if (plugins.scorers.none { it.id == scorerId }) return null
        settings.set("scorer.$typeId", scorerId)
        return retrainAndScore(typeId)
    }

    private fun counts(grades: IntArray) = IntArray(Grades.MAX).apply { grades.forEach { this[it - Grades.MIN]++ } }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        private const val RETRAIN_DELAY_SECONDS = 3L
        private const val MIN_RATINGS = 3
        private const val REVIEWS_SHOWN = 30
        private const val MATCHES_SHOWN = 3
        private const val TOP_FEATURES = 25
        private const val SCORE_CHUNK = 1024
        private const val MAX_IMPORTANCE_ITEMS = 1500
        private val MARK_KEYS = setOf(MarkLikeness.PICTURES, MarkLikeness.REVIEWS)
    }
}
