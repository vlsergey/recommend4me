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

    @Synchronized
    private fun kernels(card: Cublas): CudaKernels? {
        if (compiledFor !== card) {
            compiledFor = card
            compiled = card.kernels(GraphSearch::class.java.getResource("graph.cu")!!.readText(), NAMES)
        }
        return compiled
    }

    /** The neighbours of a work in the graph (`K` of `graph.cu`). */
    const val K = 16

    fun search(search: PairSearch, models: List<Logistic.Laid>, card: Cublas): Likeliest? {
        val all = AllWorks.of(search) ?: return null
        return card.lock.withLock {
            val kernels = kernels(card) ?: error("the kernels of the graph were not made")
            CardRoom(card).use { room ->
                val started = System.currentTimeMillis()
                val onCard = AllOnCard(all, room)
                // Of every work the works of an author one with its: every one a candidate
                val titles = Titles.of(all)
                val ofAuthors = Lists.of(sameAuthor(all))
                // Of every work the works of an author one with its, and those of the very same title: every one a
                // candidate, however many works are near it by the graph
                val extra =
                    Lists.of(List(all.count) { u -> (ofAuthors.of(u) + titles.sameAs(u)).distinct().toIntArray() })
                val learnt = System.currentTimeMillis()
                // What a pair is scored by, of every work: the descent and the scoring alike
                val works = onCard.scoring(models, room)
                // The graph of the likeliest by the models (user, 2026-11-18, of a graph of the titles alone: "you made
                // that up yourself"), begun of the works of one author and of one title, not of works at random
                val seeds = Seeds.of(all, ofAuthors, titles)
                val graph = Descent(all.count, seeds, works, kernels, room).run()
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

    /**
     * Of every work, the other works of an author one with its, by the kin of its authors: of every author its works,
     * then of every work those of its kin, sorted, each once — the works side by side.
     */
    private fun sameAuthor(all: AllWorks): List<IntArray> {
        val authors = (all.authors.entries.maxOrNull() ?: -1) + 1
        val count = IntArray(authors + 1)
        all.authors.entries.forEach { count[it + 1]++ }
        for (a in 0 until authors) count[a + 1] += count[a]
        val works = IntArray(all.authors.entries.size)
        val filled = count.copyOf(authors)
        for (u in 0 until all.count) for (q in all.authors.at[u] until all.authors.at[u + 1]) {
            works[filled[all.authors.entries[q]]++] = u
        }
        return (0 until all.count)
            .toList()
            .parallelStream()
            .map { u ->
                var size = 0
                for (q in all.kin.at[u] until all.kin.at[u + 1]) {
                    val a = all.kin.entries[q]
                    if (a < authors) size += count[a + 1] - count[a]
                }
                val out = IntArray(size)
                var at = 0
                for (q in all.kin.at[u] until all.kin.at[u + 1]) {
                    val a = all.kin.entries[q]
                    if (a < authors) for (p in count[a] until count[a + 1]) out[at++] = works[p]
                }
                out.sort()
                out.distinct().filter { it != u }.toIntArray()
            }
            .toList()
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
 * EVERY WORK OF A SEARCH ON THE CARD ([AllWorks], one numbering): the titles' and the authors' names' trigrams, the
 * descriptions — noughts of a work of none, or of another encoder's —, the lengths, the facets, the sizes, the values,
 * the authors and their kin, the works answered on.
 */
internal class AllOnCard(private val all: AllWorks, room: CardRoom) {
    private val began = System.currentTimeMillis()
    private val n = all.count.toLong()
    val titles = room.bytes(n * all.width * HALF_BYTES)
    val authorNames = room.bytes(n * all.width * HALF_BYTES)
    val descriptions = room.bytes(n * maxOf(1, all.described) * HALF_BYTES).also { it.zero() }
    val titleVectors = room.bytes(n * maxOf(1, all.titled) * HALF_BYTES).also { it.zero() }
    val letters = room.bytes(LETTERED * n * all.width * HALF_BYTES)

    init {
        all.sites.forEachIndexed { a, s ->
            val at = all.start[a].toLong()
            titles.upload(s.titles, 0, s.count, at * all.width * HALF_BYTES)
            authorNames.upload(s.authorNames, 0, s.count, at * all.width * HALF_BYTES)
            if (all.described > 0 && s.descriptions.cols == all.described)
                descriptions.upload(s.descriptions, 0, s.count, at * all.described * HALF_BYTES)
            if (all.titled > 0 && s.titleVectors.cols == all.titled)
                titleVectors.upload(s.titleVectors, 0, s.count, at * all.titled * HALF_BYTES)
            // The texts one after another, of every work each
            for (k in 0 until LETTERED) letters.upload(s.letters[k], 0, s.count, (k * n + at) * all.width * HALF_BYTES)
        }
    }

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
            titles,
            authorNames,
            descriptions,
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
            titleVectors,
            titled,
            letters,
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
    private val n: Int,
    /** Of every work, its first neighbours: the rest of its K at random. */
    private val seeds: Lists,
    /** What a pair is scored by ([AllOnCard.scoring]). */
    private val works: List<Any>,
    private val kernels: CudaKernels,
    private val room: CardRoom,
) {
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
                *(listOf(graph, fresh, next, nextFresh, changed) + works).toTypedArray(),
            )
            changed.download(back, 2L * Long.SIZE_BYTES)
            val ints = back.ints(0, 4)
            val count = (ints[0].toLong() and LOW) or (ints[1].toLong() shl Int.SIZE_BITS)
            val pairs = (ints[2].toLong() and LOW) or (ints[3].toLong() shl Int.SIZE_BITS)
            graph = next.also { next = graph }
            fresh = nextFresh.also { nextFresh = fresh }
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
        return Lists.of(
            List(all.count) { u ->
                val own = LinkedHashSet<Int>()
                for (q in ofAuthors.at[u] until minOf(ofAuthors.at[u + 1], ofAuthors.at[u] + k / 2)) {
                    own += ofAuthors.entries[q]
                }
                // The works of the same title
                titles.sameAs(u).take(k - own.size).forEach { own += it }
                own.toIntArray()
            }
        )
    }
}

/**
 * THE WORKS OF THE VERY SAME TITLE: of every work the hash of its title's trigrams, the works of one hash one title —
 * of a title many works bear, the [MOST] nearest it in the order of the hashes. [shared] the titles several works bear,
 * [largest] the most works of one.
 */
internal class Titles
private constructor(
    private val hashes: LongArray,
    private val order: IntArray,
    private val place: IntArray,
) {
    val shared: Int
    val largest: Int

    init {
        var groups = 0
        var most = 0
        var from = 0
        while (from < order.size) {
            var to = from + 1
            while (to < order.size && hashes[order[to]] == hashes[order[from]]) to++
            if (to - from > 1) groups++
            most = maxOf(most, to - from)
            from = to
        }
        shared = groups
        largest = most
    }

    /** The other works of the [u]-th one's title, the nearest first, [MOST] at most. */
    fun sameAs(u: Int): IntArray {
        val out = ArrayList<Int>()
        var step = 1
        while (out.size < MOST && step <= MOST) {
            var any = false
            for (at in intArrayOf(place[u] - step, place[u] + step)) {
                if (at in order.indices && hashes[order[at]] == hashes[u]) {
                    out += order[at]
                    any = true
                }
            }
            if (!any) break
            step++
        }
        return out.toIntArray()
    }

    companion object {
        /** The works of one title taken of a work at most. */
        const val MOST = 256

        private const val HASH_START = 1125899906842597L
        private const val HASH_PRIME = 31L

        fun of(all: AllWorks): Titles {
            val hashes = LongArray(all.count)
            all.sites.forEachIndexed { a, s ->
                val row = FloatArray(s.width)
                for (i in 0 until s.count) {
                    s.titles.take(i, row)
                    var h = HASH_START
                    row.forEach { h = h * HASH_PRIME + java.lang.Float.floatToIntBits(it) }
                    hashes[all.start[a] + i] = h
                }
            }
            val order = (0 until all.count).sortedWith(compareBy({ hashes[it] }, { it })).toIntArray()
            val place = IntArray(all.count).also { p -> order.forEachIndexed { at, u -> p[u] = at } }
            return Titles(hashes, order, place)
        }
    }
}

/** The [u]-th row's entries. */
internal fun Lists.of(u: Int): IntArray = entries.copyOfRange(at[u], at[u + 1])
