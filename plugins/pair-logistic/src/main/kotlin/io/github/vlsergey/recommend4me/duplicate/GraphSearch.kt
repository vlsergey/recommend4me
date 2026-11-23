package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import java.util.Random
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GraphSearch::class.java)

/**
 * THE SEARCH BY A GRAPH OF THE LIKELIEST (user, 2026-11-18: "the graph index, I like it"), on the card: not every pair
 * of works compared, but of every work its [K] nearest — of every model its share of them, the likeliest by it, by
 * everything a search compares two works by — found by descent ([Descent]), and those scored by every model as every
 * search scores a pair: of every work, the likeliest of every model kept ([Likeliest]). Null of works whose trigrams
 * are not of one width: their search is every pair's ([CardSearch]).
 */
internal object GraphSearch {
    private val NAMES = listOf("descend", "scored")

    private var compiledFor: Cublas? = null
    private var compiled: CudaKernels? = null

    /** The kernels of the graph compiled for the [card], once; told to the log, what the card made of each. */
    @Synchronized
    internal fun kernels(card: Cublas): CudaKernels? {
        if (compiledFor !== card) {
            compiledFor = card
            compiled =
                card.kernels(GraphSearch::class.java.getResource("graph.cu")!!.readText(), NAMES)?.also { k ->
                    log.info("the kernels of the graph: {}", BOUNDED.joinToString { "$it ${k.fit(it)}" })
                }
        }
        return compiled
    }

    /** The kernels bounded to fill a multiprocessor ([CudaKernels.blocks]). */
    val BOUNDED = listOf("descend", "scored")

    /** The neighbours of a work in the graph (`K` of `graph.cu`). */
    const val K = 16

    /**
     * The search by the graph of the [models] on the [card]; [keeps]: whether a step of the descent keeps the chances
     * of the neighbours of the one before and the neighbours of a work of no new candidate ([Descent]) — the same graph
     * as of every neighbour scored anew, tested so.
     */
    fun search(search: PairSearch, models: List<Logistic.Laid>, card: Cublas, keeps: Boolean = true): Likeliest? {
        val all = AllWorks.of(search) ?: return null
        return card.lock.withLock {
            val kernels = kernels(card) ?: error("the kernels of the graph were not made")
            CardRoom.of(card, AllOnCard.need(all, models.size)).use { room ->
                val started = System.currentTimeMillis()
                val onCard = AllOnCard(all, room)
                val titles = Titles.of(all)
                val ofAuthors = WorksAlike.ofAuthors(all)
                // Of every work the works of an author one with its, and those of the very same title: every one a
                // candidate, however many works are near it by the graph
                val extra = WorksAlike.candidates(ofAuthors, titles.same)
                val learnt = System.currentTimeMillis()
                // What a pair is scored by, of every work: the descent and the scoring alike
                val works = onCard.scoring(models, room)
                // The graph of the likeliest by the models (user, 2026-11-18, of a graph of the titles alone: "you made
                // that up yourself"), begun of the works of one author and of one title, not of works at random
                val seeds = Seeds.of(all, ofAuthors, titles)
                val graph = Descent(models.size, seeds, works, kernels, room, keeps).run()
                val descended = System.currentTimeMillis()
                val found = scored(all, onCard, graph, extra, works, models.size, kernels, room)
                log.info(
                    "pairs searched by the graph in {} ms: put on the card {}, the works of one author and of one " +
                        "title {} ({} pairs; titles borne by several works {}, by {} at most), descended {}, scored {}",
                    System.currentTimeMillis() - started,
                    onCard.millis,
                    learnt - started - onCard.millis,
                    extra.entries.size,
                    titles.shared,
                    titles.largest,
                    descended - learnt,
                    System.currentTimeMillis() - descended,
                )
                found
            }
        }
    }

    /** Of every work its neighbours, theirs and its [extra] candidates scored by every model, the likeliest kept. */
    private fun scored(
        all: AllWorks,
        card: AllOnCard,
        graph: Cublas.DeviceBytes,
        extra: Lists,
        works: List<Any>,
        models: Int,
        kernels: CudaKernels,
        room: CardRoom,
    ): Likeliest {
        val search = all.search
        val n = all.count
        val best = room.bytes(n.toLong() * models * Int.SIZE_BYTES)
        val scores = room.bytes(n.toLong() * models * Float.SIZE_BYTES)
        val head = listOf(graph, room.ints(extra.at), room.ints(extra.entries), card.passAt, card.passed, best, scores)
        kernels.launch("scored", n.toLong() * WARP, *(head + works).toTypedArray())
        val bestBack = room.pinned(n.toLong() * models * Int.SIZE_BYTES)
        val scoresBack = room.pinned(n.toLong() * models * Float.SIZE_BYTES)
        best.download(bestBack, n.toLong() * models * Int.SIZE_BYTES)
        scores.download(scoresBack, n.toLong() * models * Float.SIZE_BYTES)
        val chosen = bestBack.ints(0, n * models)
        val score = scoresBack.floats(0, n * models)
        val likeliest = Likeliest(search, models)
        for (u in 0 until n) for (k in 0 until models) {
            val j = chosen[u * models + k]
            if (j < 0) continue
            val a = all.siteOf[u]
            val b = all.siteOf[j]
            likeliest.offer(a, u - all.start[a], k, score[u * models + k].toDouble(), b, j - all.start[b])
        }
        return likeliest
    }

    /** The threads of a warp: a work's neighbours scored by a warp. */
    private const val WARP = 32
}

/**
 * EVERY WORK OF A SEARCH ON THE CARD ([AllWorks], one numbering), its rows kept small there as in the main memory
 * (owner, 2026-11-20: every work's rows made whole halves took 13 of the card's 16 GB, Windows moved the rest to the
 * main memory and the graph ran 70 times slower): the trigrams of the titles, of the authors' names, of the sites'
 * names of the fandoms and lines of the characters sparse ([SparseOnCard]); the descriptions' and the titles' vectors a
 * byte a number with a scale a work — noughts of a work of none, or of another encoder's —, the description's trigrams
 * so (2026-11-23: of halves whole, a pair of the descent read 1 KB of them; the chances the search keeps are of its
 * halves, of the main memory); the lengths, the facets, the sizes, the values, the authors and their kin, the works
 * answered on.
 */
internal class AllOnCard(private val all: AllWorks, room: CardRoom) {
    companion object {
        /**
         * The card's memory the graph of [all] needs at least, of [models] models: its rows, their lengths and marks,
         * the values, the authors and the works answered on, the graph and its candidates — a block of less refused,
         * the tiles searching then.
         */
        fun need(all: AllWorks, models: Int): Long {
            val n = all.count.toLong()
            fun sparse(of: (SiteWorks) -> io.github.vlsergey.recommend4me.matrix.Rows) =
                (n + 1) * Int.SIZE_BYTES +
                    all.sites.sumOf { SparseOnCard.sparse(of(it)).entryCount.toLong() } * Int.SIZE_BYTES
            val rows =
                sparse { it.titles } +
                    sparse { it.authorNames } +
                    sparse { it.letters[LetteredText.FANDOMS.ordinal] } +
                    sparse { it.letters[LetteredText.CHARACTERS.ordinal] } +
                    n * (maxOf(1, all.described) + maxOf(1, all.titled) + all.width + 3L * Float.SIZE_BYTES)
            val lists =
                (all.values.entries.size.toLong() * 2 +
                    all.kin.entries.size +
                    all.authors.entries.size +
                    all.passed.entries.size +
                    4 * n) * Int.SIZE_BYTES
            val graph =
                n * GraphSearch.K * (2L * Int.SIZE_BYTES + 2 + 2L * models * Float.SIZE_BYTES) +
                    n * models * 2L * Float.SIZE_BYTES
            return rows + lists + graph
        }
    }

    private val began = System.currentTimeMillis()
    private val n = all.count.toLong()
    val titles = SparseOnCard.of(all.sites.map { it.titles }, room)
    val authorNames = SparseOnCard.of(all.sites.map { it.authorNames }, room)
    val fandoms = SparseOnCard.of(all.sites.map { it.letters[LetteredText.FANDOMS.ordinal] }, room)
    val characters = SparseOnCard.of(all.sites.map { it.letters[LetteredText.CHARACTERS.ordinal] }, room)
    val descriptions = BytesOnCard.of(all, { it.descriptions }, all.described, room)
    val titleVectors = BytesOnCard.of(all, { it.titleVectors }, all.titled, room)
    val descriptionLetters = BytesOnCard.of(all, { it.letters[LetteredText.DESCRIPTION.ordinal] }, all.width, room)

    val titleLengths = room.floatsOf(all.sites.flatMap { it.titleLengths.asList() }.toFloatArray())
    val authorLengths = room.floatsOf(all.sites.flatMap { it.authorLengths.asList() }.toFloatArray())
    val described =
        room.marks(
            all.sites.flatMap { s -> s.described.map { it && s.descriptions.cols == all.described } }.toBooleanArray()
        )
    val titled =
        room.marks(all.sites.flatMap { s -> s.titled.map { it && s.titleVectors.cols == all.titled } }.toBooleanArray())
    val letterLengths =
        room.floatsOf(
            (0 until LETTERED).flatMap { k -> all.sites.flatMap { it.letterLengths[k].asList() } }.toFloatArray()
        )
    val comparedTitles = room.marks(BooleanArray(all.comparedTitles.size) { all.comparedTitles[it] != 0.toByte() })
    val facets = room.longs(all.sites.flatMap { it.facets.asList() }.toLongArray())
    val sizes = room.floatsOf(all.sizes)
    val siteOf = room.ints(all.siteOf)
    val comparedDescriptions =
        room.marks(BooleanArray(all.comparedDescriptions.size) { all.comparedDescriptions[it] != 0.toByte() })
    val valueAt = room.ints(all.values.at)
    val values = room.ints(all.values.entries)
    val valueWeights = room.floatsOf(all.valueWeights)
    val familyOf = room.ints(all.search.familyOf)
    val kinAt = room.ints(all.kin.at)
    val kin = room.ints(all.kin.entries)
    val authorAt = room.ints(all.authors.at)
    val authors = room.ints(all.authors.entries)
    val passAt = room.ints(all.passed.at)
    val passed = room.ints(all.passed.entries)

    /** How long it took to put them there. */
    val millis = System.currentTimeMillis() - began

    /**
     * What a pair is scored by on the card — the works' rows and every two sites' weights of the [models] — as the
     * kernels take it (`WORKS_PARAMS` of `graph.cu`).
     */
    fun scoring(models: List<Logistic.Laid>, room: CardRoom): List<Any> {
        val search = all.search
        val weights =
            search.sites
                .flatMap { x ->
                    search.sites.flatMap { y -> models.flatMap { it.between(x.source, y.source).flat().asList() } }
                }
                .toFloatArray()
        return listOf(
            all.count,
            siteOf,
            search.sites.size,
            comparedDescriptions,
            all.width,
            maxOf(1, all.described),
            titles.at,
            titles.entries,
            authorNames.at,
            authorNames.entries,
            descriptions.bytes,
            descriptions.scales,
            titleLengths,
            authorLengths,
            described,
            facets,
            search.sizes.size,
            sizes,
            valueAt,
            values,
            valueWeights,
            familyOf,
            kinAt,
            kin,
            authorAt,
            authors,
            comparedTitles,
            maxOf(1, all.titled),
            titleVectors.bytes,
            titleVectors.scales,
            titled,
            descriptionLetters.bytes,
            descriptionLetters.scales,
            fandoms.at,
            fandoms.entries,
            characters.at,
            characters.entries,
            letterLengths,
            models.size,
            room.floatsOf(weights),
            Logistic.FLAT_HEAD + 2 * search.families.size,
        )
    }
}

/**
 * THE GRAPH OF THE NEAREST BY DESCENT: of every work [GraphSearch.K] works at random, then step by step its neighbours'
 * neighbours' nearest kept (`descend`) — till few change, or [MOST_STEPS]. Told to the log: how many changed at every
 * step.
 */
internal class Descent(
    /** The models scoring a pair. */
    private val models: Int,
    /** Of every work, its first neighbours: the rest of its K at random. */
    private val seeds: Lists,
    /** What a pair is scored by ([AllOnCard.scoring]). */
    private val works: List<Any>,
    private val kernels: CudaKernels,
    private val room: CardRoom,
    /**
     * Whether a step keeps the chances of the neighbours of the one before, and a work of no new candidate as it is.
     */
    private val keeps: Boolean,
) {
    /** The works. */
    private val n = seeds.at.size - 1

    fun run(): Cublas.DeviceBytes {
        val k = GraphSearch.K
        val random = Random(SEED)
        val start = IntArray(n * k) { at -> (random.nextInt(maxOf(1, n - 1)) + 1 + at / k) % n }
        for (u in 0 until n) {
            val own = seeds.at[u + 1] - seeds.at[u]
            for (q in 0 until minOf(k, own)) start[u * k + q] = seeds.entries[seeds.at[u] + q]
        }
        var graph = room.ints(start)
        var next = room.bytes(n.toLong() * k * Int.SIZE_BYTES)
        // Which neighbours are new: at first every one
        var fresh = room.marks(BooleanArray(n * k) { true })
        var nextFresh = room.bytes(n.toLong() * k)
        // Every model's chance of every neighbour
        var chances = room.bytes(n.toLong() * k * models * Float.SIZE_BYTES)
        var nextChances = room.bytes(n.toLong() * k * models * Float.SIZE_BYTES)
        // How many neighbours are new, how many pairs were scored
        val changed = room.bytes(2L * Long.SIZE_BYTES)
        val back = room.pinned(2L * Long.SIZE_BYTES)
        val steps = ArrayList<String>()
        for (step in 1..MOST_STEPS) {
            val began = System.currentTimeMillis()
            changed.zero()
            kernels.launch(
                "descend",
                n.toLong() * CudaKernels.BLOCK,
                *(listOf(
                        graph,
                        fresh,
                        chances,
                        next,
                        nextFresh,
                        nextChances,
                        if (keeps && step > 1) 1 else 0,
                        changed,
                    ) + works)
                    .toTypedArray(),
            )
            changed.download(back, 2L * Long.SIZE_BYTES)
            val ints = back.ints(0, 4)
            val count = (ints[0].toLong() and LOW) or (ints[1].toLong() shl Int.SIZE_BITS)
            val pairs = (ints[2].toLong() and LOW) or (ints[3].toLong() shl Int.SIZE_BITS)
            graph = next.also { next = graph }
            fresh = nextFresh.also { nextFresh = fresh }
            chances = nextChances.also { nextChances = chances }
            steps += "$count of $pairs pairs in ${System.currentTimeMillis() - began} ms"
            if (count < n.toLong() * k / SETTLED) break
        }
        log.info(
            "the graph of {} works descended in {} steps, its neighbours changed of the pairs scored: {}",
            n,
            steps.size,
            steps,
        )
        return graph
    }

    private companion object {
        const val MOST_STEPS = 30

        /** A step changing fewer than this share of the neighbours: the graph has settled. */
        const val SETTLED = 1000

        const val SEED = 20261118L

        const val LOW = 0xffffffffL
    }
}

/**
 * THE FIRST NEIGHBOURS OF EVERY WORK IN THE GRAPH: the works of an author one with its, and those of the very same
 * title's trigrams — a title many works bear, the works nearest it in the numbering — [GraphSearch.K] at most: the
 * descent begun near where it ends.
 */
internal object Seeds {
    fun of(all: AllWorks, ofAuthors: Lists, titles: Titles): Lists {
        val k = GraphSearch.K
        val same = titles.same
        val at = IntArray(all.count + 1)
        val entries = IntArray(all.count * k)
        var size = 0
        for (u in 0 until all.count) {
            val from = size
            for (q in ofAuthors.at[u] until minOf(ofAuthors.at[u + 1], ofAuthors.at[u] + k / 2)) {
                entries[size++] = ofAuthors.entries[q]
            }
            // The works of the same title, those of an author one with it once
            val taken = size - from
            for (q in same.at[u] until minOf(same.at[u + 1], same.at[u] + k - taken)) {
                val v = same.entries[q]
                if ((from until size).none { entries[it] == v }) entries[size++] = v
            }
            at[u + 1] = size
        }
        return Lists(at, entries.copyOf(size))
    }
}
