package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.PairMeasure
import io.github.vlsergey.recommend4me.api.model.PairRow
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.duplicate.LoosePairs
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.task.inBackground
import io.github.vlsergey.recommend4me.worklist.ListFacetValues
import io.github.vlsergey.recommend4me.worklist.ListIndexing
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.i18n.LocaleContextHolder

/**
 * Two sites of one content type: what spans sources — a work on both sites, what each site says of it, the duplicates,
 * a search. Its authors: [TwoSitesAuthorsTest].
 */
// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class TwoSitesTest : TwoSitesTestBase() {

    @Autowired lateinit var listIndexing: ListIndexing

    private companion object {
        /** A page of the pairs, as the screen of the duplicates reads them. */
        const val PAGE = 100

        /** The largest page of the pairs the server gives. */
        const val OPEN_PAGE = 200
    }

    @Test
    fun `a word of different works on two of one series of a site is taken back, and refused while it holds them`() {
        // The site of the tests that has series: "<id>|<title>|<tags>|<annotation>|<pairings>|<series>"
        fun site(body: String) =
            captures.capturePage(CaptureRequest("https://site.example/work/${body.substringBefore('|')}", body))
        site("sd1|Saga One|river|The first of a saga||saga")
        site("sd2|Saga Two|river|The second of a saga||")
        val type = stores.type("books")!!
        val d1 = ItemKey("site", idOf("site", "sd1"))
        val d2 = ItemKey("site", idOf("site", "sd2"))
        fun different() =
            duplicates.answerDuplicate(
                "books",
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                    ItemRef("site", idOf("site", "sd1")),
                    ItemRef("site", idOf("site", "sd2")),
                    io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT,
                ),
            )
        different()
        background.run()
        assertEquals("DIFFERENT", type.links.word(d1, d2, Instant.now()))
        // The site puts the second in the series too: the word not heeded at once — the pair of the series —, and
        // taken back in the queue, in the journal so
        site("sd2|Saga Two|river|The second of a saga||saga")
        val shown =
            duplicates.getDuplicatePair("books", "site", idOf("site", "sd1"), "site", idOf("site", "sd2")).body!!
        assertEquals(io.github.vlsergey.recommend4me.api.model.DuplicatePair.Answer.SERIES, shown.answer)
        assertEquals(true, shown.derived)
        background.run()
        assertNull(type.links.word(d1, d2, Instant.now()))
        val taken = type.links.journal().first { setOf(it.first, it.second) == setOf(d1, d2) }
        assertTrue(taken.ofSeries && taken.answer == null && taken.before == "DIFFERENT", taken.toString())
        // Said again, the word is refused: the site's series is stronger than it
        assertEquals("seriesHolds", assertThrows<Refused> { different() }.refusal.code)
        assertNull(type.links.word(d1, d2, Instant.now()))
    }

    @Test
    fun `the cover of a work is its own item's, else another site's`() {
        alpha("c1|The Cover|sea|A work with a cover on one site|cover.png|")
        beta("c2|The Cover|sea|A work with a cover on one site")
        assertNull(items.getItem("beta", idOf("beta", "c2")).body!!.summary.cover)
        corrections.linkItem("alpha", idOf("alpha", "c1"), ItemRef("beta", idOf("beta", "c2")))
        assertEquals(
            ItemRef("alpha", idOf("alpha", "c1")),
            items.getItem("alpha", idOf("alpha", "c1")).body!!.summary.cover,
        )
        assertEquals(
            ItemRef("alpha", idOf("alpha", "c1")),
            items.getItem("beta", idOf("beta", "c2")).body!!.summary.cover,
        )
        assertEquals(
            0,
            items.getItem("beta", idOf("beta", "c2")).body!!.summary.pictureCount,
            "the pictures of the site's page stay its own",
        )
        corrections.unlinkItem("alpha", idOf("alpha", "c1"), "beta", idOf("beta", "c2"))
    }

    @Test
    fun `one work on two sites is one work — a grade or a mark of either is of both`() {
        alpha("a1|The Lighthouse|sea|A keeper of a lighthouse waits for the storm||")
        beta("b9|The Lighthouse|ocean|A keeper of a lighthouse waits for the storm")
        corrections.linkItem("alpha", idOf("alpha", "a1"), ItemRef("beta", idOf("beta", "b9")))
        gradesController.rateItem("alpha", idOf("alpha", "a1"), RatingRequest(4))
        assertEquals(4, items.getItem("beta", idOf("beta", "b9")).body!!.summary.grade)
        // The link taken back: each keeps its grade, and is its own after
        corrections.unlinkItem("alpha", idOf("alpha", "a1"), "beta", idOf("beta", "b9"))
        gradesController.rateItem("beta", idOf("beta", "b9"), RatingRequest(2))
        assertEquals(4, items.getItem("alpha", idOf("alpha", "a1")).body!!.summary.grade)
        gradesController.unrateItem("alpha", idOf("alpha", "a1"), null)
        gradesController.unrateItem("beta", idOf("beta", "b9"), null)
    }

    @Test
    fun `the form of a work is one through either of its items — what every site says stands above the field`() {
        alpha("f1|The Harbour|harbour-one|A harbour at dusk||")
        beta("f2|The Harbour|harbour-two|A harbour at dusk")
        corrections.linkItem("alpha", idOf("alpha", "f1"), ItemRef("beta", idOf("beta", "f2")))
        fun field(source: String, item: Long) =
            items.getItem(source, item).body!!.allFacets.single { it.facet == Books.TAGS.key }
        val one = field("alpha", idOf("alpha", "f1"))
        val other = field("beta", idOf("beta", "f2"))
        assertEquals(listOf("harbour-one"), one.said.orEmpty().single { it.source == "alpha" }.words.map { it.name })
        assertEquals(listOf("harbour-two"), one.said.orEmpty().single { it.source == "beta" }.words.map { it.name })
        assertEquals(one.said, other.said)
        assertEquals(
            one.propertyValues.map { it.key to it.fromSite }.toSet(),
            other.propertyValues.map { it.key to it.fromSite }.toSet(),
        )
        corrections.unlinkItem("alpha", idOf("alpha", "f1"), "beta", idOf("beta", "f2"))
    }

    @Test
    fun `a word on a pair of duplicates is carried across one work — said of one of two works one, of the other`() {
        alpha("m1|The Mill|river|A miller and his river||")
        beta("m2|The Mill|river|A miller and his river")
        beta("m3|The Mill, translated|river|A miller and his river, in another tongue")
        alpha("m4|The Mill Again|river|The miller's son||")
        // The tags of two sites are two till the user merges them: merged, the works of both share it
        tags.relateTags(TagRelationRequest(tagOf("alpha", "river"), tagOf("beta", "river"), TagRelation.MERGED))
        val links = stores.type("books")!!.links
        fun key(source: String, id: Long) = ItemKey(source, id)
        fun answer(a: ItemKey, b: ItemKey, verdict: io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict) =
            duplicates.answerDuplicate(
                "books",
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                    ItemRef(a.source, a.id),
                    ItemRef(b.source, b.id),
                    verdict,
                ),
            )
        fun relation(a: ItemKey, b: ItemKey) = links.relations()[links.ordered(a, b)]
        fun different(a: ItemKey, b: ItemKey) = links.ordered(a, b) in links.distincts()
        val m1 = key("alpha", idOf("alpha", "m1"))
        val m2 = key("beta", idOf("beta", "m2"))
        val m3 = key("beta", idOf("beta", "m3"))
        val m4 = key("alpha", idOf("alpha", "m4"))

        twoWorksCompared()

        answer(m1, m2, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        assertEquals(
            io.github.vlsergey.recommend4me.api.model.DuplicatePair.Answer.SAME,
            duplicates
                .getDuplicatePair("books", "alpha", idOf("alpha", "m1"), "beta", idOf("beta", "m2"))
                .body!!
                .answer,
        )
        // Of one of two works one, a translation: of the other too
        answer(m1, m3, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.TRANSLATION)
        assertEquals("TRANSLATION", relation(m2, m3))
        // The window of the work tells it, of whichever item, and what each site says
        val window = items.getItem("beta", idOf("beta", "m2")).body!!
        assertEquals(listOf("beta/${idOf("beta", "m3")}"), window.relations.map { "${it.source}/${it.item}" })
        assertEquals(
            setOf("alpha/${idOf("alpha", "m1")}", "beta/${idOf("beta", "m2")}"),
            window.sites.map { "${it.source}/${it.item}" }.toSet(),
        )
        assertEquals(
            listOf("alpha/${idOf("alpha", "m4")}"),
            items.getItem("alpha", idOf("alpha", "m4")).body!!.sites.map { "${it.source}/${it.item}" },
        )
        assertEquals(404, duplicates.getItemDuplicates("alpha", ABSENT_WORK).statusCode.value())
        // Taken back of one pair: of every pair of the two works' groups
        duplicates.unanswerDuplicate("books", "beta", idOf("beta", "m2"), "beta", idOf("beta", "m3"))
        assertEquals(null, relation(m1, m3))
        assertEquals(null, relation(m2, m3))

        // A word said before the works were one: carried to the work that joins them
        answer(m4, m3, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        answer(m4, m1, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        assertTrue(different(m4, m2), "m4 is different from m1, so from m2, one with it")
        answer(m3, m2, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        // m3 joins m1 and m2: m4, different from them, different from m3 as it was; a word of the group's own two taken
        // away
        assertTrue(different(m4, m3))
        assertEquals(null, relation(m1, m3))

        for ((a, b) in listOf(m1 to m2, m2 to m3, m4 to m1, m4 to m3)) duplicates.unanswerDuplicate(
            "books",
            a.source,
            a.id,
            b.source,
            b.id,
        )
    }

    /**
     * Any two works compared as the screen of duplicates shows a pair: their values as compared, its layers kept by the
     * last search or worked out as it is shown.
     */
    private fun twoWorksCompared() {
        // The two works opened first, as the user would: a work opened is made on the spot — its texts encoded, the
        // model's values of them made — and the window of a pair shows the values its screen gives
        listOf("alpha" to idOf("alpha", "m1"), "beta" to idOf("beta", "m2")).forEach { (source, item) ->
            items.getItem(source, item)
        }
        // Any two works compared as the screen of duplicates shows a pair, the word said of them with it
        val compared =
            duplicates.getDuplicatePair("books", "beta", idOf("beta", "m2"), "alpha", idOf("alpha", "m1")).body!!
        assertEquals(
            listOf("alpha/${idOf("alpha", "m1")}", "beta/${idOf("beta", "m2")}"),
            listOf(compared.first, compared.second).map { "${it.source}/${it.item}" },
        )
        assertEquals("The Mill", compared.second.title)
        assertEquals(null, compared.answer)
        // Each with the values of the layer its screen gives it — the site's tag only offered there, not among them —,
        // compared by what they share
        val tags = compared.rows.firstOrNull { it.key == "tags" }
        listOf(compared.first to tags?.first, compared.second to tags?.second).forEach { (w, cell) ->
            val given =
                items
                    .getItem(w.source, w.item)
                    .body!!
                    .allFacets
                    .filter { it.editable && it.facet == "tags" }
                    .flatMap { f -> f.propertyValues }
                    .filter { v ->
                        v.corrected == FacetValueInfo.Corrected.ADDED ||
                            v.corrected == FacetValueInfo.Corrected.CONFIRMED ||
                            (v.corrected == null && v.offered != true && v.implied != true)
                    }
                    .map { it.name }
                    .sorted()
            assertEquals(given, cell?.propertyValues.orEmpty().map { it.name })
        }
        if (tags?.group == PairRow.Group.COMPARED)
            assertEquals(listOf(PairMeasure.Kind.VALUES), tags.measures.map { it.kind }, "$tags")
        // Its layers worked out as it is shown, though no search kept it: the same title, the same vector; its values
        // compared as the index of the list keeps them — what each work is given — the same as the search's (below)
        fun pair() =
            duplicates.getDuplicatePair("books", "beta", idOf("beta", "m2"), "alpha", idOf("alpha", "m1")).body!!
        background.run()
        fun title() =
            pair().rows.single { it.key == "title" }.measures.firstOrNull { it.kind == PairMeasure.Kind.LETTERS }?.value
        assertTrue(title() != null, "the titles encoded")
        assertEquals(1.0, title()!!.toDouble(), 1e-3)
        keptAsWorkedOut()
        // The pairs chosen by a title wholly alike, and by the authors: those alone, every one of them. Every page of
        // them: the other classes' pairs too, in the one application of the tests — whose answers the model of the
        // pairs is fitted on too, the chance of a pair theirs as much as this scenario's
        fun chosen(authors: Boolean, title: Boolean) =
            generateSequence(0) { it + PAGE }
                .map { offset ->
                    duplicates
                        .getDuplicates("books", true, offset, PAGE, DuplicateSort.CHANCE, null, authors, title)
                        .body!!
                        .pairs
                }
                .takeWhile { it.isNotEmpty() }
                .flatten()
                .toList()
        fun named(p: DuplicatePair) = Triple(p.first.item, p.second.item, p.first.source + p.second.source)
        val every = chosen(authors = false, title = false)
        val alike = every.filter { it.first.title.trim().equals(it.second.title.trim(), ignoreCase = true) }
        assertEquals(alike.map(::named).toSet(), chosen(authors = false, title = true).map(::named).toSet())
        assertTrue(alike.size < every.size)
        assertTrue(chosen(authors = true, title = false).all { it.sameAuthor })
        assertEquals(
            404,
            duplicates.getDuplicatePair("books", "beta", ABSENT_WORK, "alpha", idOf("alpha", "m1")).statusCode.value(),
        )
    }

    /** A search keeps how rare every value is, and the pair as the search keeps it is the pair worked out loose. */
    private fun keptAsWorkedOut() {
        // The values in the index of the list once the background has written them: of
        // the values the index of the list holds once the background has written them
        // — of the one or the other: a site's tag the model doubts is given or not as the model is fitted
        fun listed() =
            ListFacetValues.of(
                listIndexes.of("books"),
                listOf(ItemKey("alpha", idOf("alpha", "m1")), ItemKey("beta", idOf("beta", "m2"))),
            )
        background.run()
        assertTrue(listed().values.any { "tags" in it }, "the tags in the index: ${listed()}")
        val type = stores.type("books")!!
        val before = type.duplicates.search()?.madeAt
        duplicates.searchDuplicates("books")
        background.run()
        assertTrue(type.duplicates.search()?.madeAt.let { it != null && it != before }, "searched")
        val river = "tags:${tagOf("alpha", "river")}"
        assertTrue(type.duplicates.rarity.of(listOf(river)).isNotEmpty(), "$river of the index's ${listed()}")
        // Of the pair of this scenario's own works the search kept: a pair of another class's, or of another scenario
        // of
        // this one, may be answered or one work already, and is no open pair (in some orders of the classes)
        val own = setOf(ItemKey("alpha", idOf("alpha", "m1")), ItemKey("beta", idOf("beta", "m2")))
        val kept = buildList {
            type.duplicates.reads.forEach { add(it) }
        }
            .first { setOf(it.first, it.second) == own }
        val worked =
            LoosePairs.candidate(
                type,
                type.duplicates.search()!!.encoder,
                kept.first,
                kept.second,
                listIndexes.of("books"),
            )!!
        assertEquals(kept.title, worked.title, 1e-3f)
        assertEquals(kept.facets.keys, worked.facets.keys)
        kept.facets.forEach { (k, v) -> assertEquals(v, worked.facets.getValue(k), 1e-3f, k) }
        assertEquals(kept.sameAuthor, worked.sameAuthor)
        pageChosenInTheDatabase(kept.first, kept.second)
    }

    /**
     * A page of the pairs chosen in the database: the open ones by the chance kept with them, the likeliest first, of
     * the total counted alike; a pair answered struck out of them at once and among those of its word, and back when
     * the word is taken back.
     */
    private fun pageChosenInTheDatabase(a: ItemKey, b: ItemKey) {
        fun open() = duplicates.getDuplicates("books", true, 0, 200, DuplicateSort.CHANCE, null, false, false).body!!
        fun different() =
            duplicates
                .getDuplicates(
                    "books",
                    false,
                    0,
                    200,
                    DuplicateSort.CHANCE,
                    listOf(io.github.vlsergey.recommend4me.api.model.DuplicateVerdict.DIFFERENT),
                    false,
                    false,
                )
                .body!!
        // Of every page: the other classes' pairs are many, in the one application of the tests, and of a page of
        // the 200 likeliest the pair of [a] and [b] fell out in some orders of the classes
        fun every(first: io.github.vlsergey.recommend4me.api.model.DuplicatePage, open: Boolean) =
            first.pairs +
                (first.pairs.size until first.total step OPEN_PAGE).flatMap { offset ->
                    duplicates
                        .getDuplicates(
                            "books",
                            open,
                            offset,
                            OPEN_PAGE,
                            DuplicateSort.CHANCE,
                            if (open) null
                            else listOf(io.github.vlsergey.recommend4me.api.model.DuplicateVerdict.DIFFERENT),
                            false,
                            false,
                        )
                        .body!!
                        .pairs
                }
        fun has(page: io.github.vlsergey.recommend4me.api.model.DuplicatePage, open: Boolean = true) =
            every(page, open).any {
                setOf(it.first.source to it.first.item, it.second.source to it.second.item) == keysOf(a, b)
            }
        val before = open()
        assertEquals(minOf(before.total, 200), before.pairs.size)
        val chances = before.pairs.map { it.chance!!.toDouble() }
        assertEquals(chances.sortedDescending(), chances, "the likeliest first")
        assertTrue(has(before), "$a, $b among the open pairs")
        duplicates.answerDuplicate(
            "books",
            io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                ItemRef(a.source, a.id),
                ItemRef(b.source, b.id),
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT,
            ),
        )
        assertTrue(!has(open()), "struck out of the open pairs")
        assertEquals(before.total - 1, open().total)
        assertTrue(has(different(), open = false), "among those said different")
        duplicates.unanswerDuplicate("books", a.source, a.id, b.source, b.id)
        assertTrue(has(open()), "back among the open pairs")
        // Made one work by another way than its own answer — a work's screen —: struck out as the page is chosen
        corrections.linkItem(a.source, a.id, ItemRef(b.source, b.id))
        assertTrue(!has(open()), "one work by a work's screen: no open pair")
        corrections.unlinkItem(a.source, a.id, b.source, b.id)
        duplicates.unanswerDuplicate("books", a.source, a.id, b.source, b.id)
    }

    private fun keysOf(a: ItemKey, b: ItemKey) = setOf(a.source to a.id, b.source to b.id)

    @Test
    fun `every word on a pair is in the journal, a word undone gives the pair the word before`() {
        alpha("j1|The Well|water|A well of water||")
        beta("j2|The Well|water|A well of water")
        val links = stores.type("books")!!.links
        val (j1, j2) = ItemKey("alpha", idOf("alpha", "j1")) to ItemKey("beta", idOf("beta", "j2"))
        fun answer(verdict: io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict) =
            duplicates.answerDuplicate(
                "books",
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                    ItemRef(j1.source, j1.id),
                    ItemRef(j2.source, j2.id),
                    verdict,
                ),
            )
        fun journal() =
            duplicates.getDuplicateJournal("books", 0, 200).body!!.words.filter {
                it.first.item == idOf("alpha", "j1") && it.second.item == idOf("beta", "j2")
            }
        fun word() = links.word(j1, j2, Instant.now())

        answer(io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        answer(io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        assertEquals(
            listOf("DIFFERENT" to "SAME", "SAME" to null),
            journal().map { it.answer?.name to it.before?.name },
        )
        assertEquals(listOf(true, false), journal().map { it.undoable })
        assertEquals("The Well", journal().first().first.title)

        // Undone, the pair is one work again — a word of the journal itself; an older word is not undone
        val older = journal().last().id
        duplicates.undoDuplicateWord("books", journal().first().id)
        assertEquals("SAME", word())
        assertEquals("SAME" to "DIFFERENT", journal().first().let { it.answer?.name to it.before?.name })
        assertFailsWith<Refused> { duplicates.undoDuplicateWord("books", older) }
        // Taken back and undone: the word before stands again
        duplicates.unanswerDuplicate("books", j1.source, j1.id, j2.source, j2.id)
        assertEquals(null, word())
        duplicates.undoDuplicateWord("books", journal().first().id)
        assertEquals("SAME", word())
        duplicates.unanswerDuplicate("books", j1.source, j1.id, j2.source, j2.id)
    }

    @Test
    fun `one work written at once by several writers — page sent, page downloaded — whole by each, no key broken`() {
        val context = contexts.of("alpha")
        val threads = 8
        repeat(30) { round ->
            val site = "race$round"
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            val start = java.util.concurrent.CountDownLatch(1)
            val done =
                (1..threads).map { k ->
                    pool.submit<Unit> {
                        start.await()
                        val id =
                            context.items
                                .upsert(
                                    ItemHead(site, "https://alpha.example/work/$site", "Race $k", version = "v$k"),
                                    Instant.now(),
                                )
                                .id
                        context.items.setFacet(id, Books.TAG.key, listOf(FacetValue("tag$k", "Tag $k")))
                        context.items.setTexts(id, mapOf("annotation" to "Written by $k"))
                        context.items.setNumbers(id, mapOf("likes" to k.toDouble()))
                    }
                }
            start.countDown()
            done.forEach { it.get() }
            pool.shutdown()
            val id = idOf("alpha", site)
            val head = assertNotNull(context.items.find(id))
            assertTrue(head.title.startsWith("Race "))
            assertEquals(1, context.items.facets(id)[Books.TAG.key]?.size, "one writer's tags whole")
        }
    }

    @Test
    fun `a tag the user gave a work finds it at once`() {
        // The line read as the interface's language, English: the works are of English
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        alpha("q3|Deep water|water|Waves over the reef||")
        // Indexed as the site wrote it first
        background.run()
        assertTrue(
            items.listItems("books", ItemQuery(search = "water")).body!!.items.any {
                it.item == idOf("alpha", "q3")
            }
        )
        corrections.correctFacet(
            "alpha",
            idOf("alpha", "q3"),
            FacetCorrection(facet = "tags", added = true, name = "Mermaids"),
        )
        background.run()
        assertTrue(
            items
                .listItems("books", ItemQuery(search = "mermaids", sort = ItemSort.SCORE))
                .body!!
                .items
                // Of the works tagged so — the other scenarios' too, in the one application of the tests
                .any { it.item == idOf("alpha", "q3") },
            "document ${
                searchDocuments
                    .documents(stores.source("alpha")!!, listOf(idOf("alpha", "q3")))
                    .values
                    .single()
                    .fields
                    .map { it.key to it.values }
            }",
        )
    }

    @Test
    fun `a work's fingerprint read of a portion of works is its document's, made one work at a time`() {
        alpha("f1|Prints|print|A keeper of prints||")
        alpha("f2|Other prints|print|Prints of another kind||")
        val s = stores.source("alpha")!!
        // Of the works as the background leaves them
        background.run()
        var told = ""
        val alike = inBackground {
            val read = searchDocuments.printsOf(s, s.items.keys().map { it.id }, s.hierarchies())
            val keys = read.keys.toList()
            // Each document made apart, as an update makes a few: what one reads of the others is not in it
            val made = keys.associateWith { k ->
                searchDocuments.documents(s, listOf(k.id), models = false).values.single().fingerprint
            }
            told = "read ${keys.associateWith { read[it] }}, made $made"
            keys.isNotEmpty() && made == keys.associateWith { read[it] }
        }
        assertTrue(alike, told)
    }

    @Test
    fun `a search finds the works by their words, the likest first`() {
        // Words no other scenario's works say: of the one application of the tests, a work of the other words of the
        // line found by meaning as near would stand in their way
        alpha("q1|Night watch|watch|An orchard keeper counts the cider barrels at night||")
        alpha("q2|Gardens|garden|Roses and tulips in a spring garden||")
        background.run()
        val found =
            items
                .listItems("books", ItemQuery(search = "cider barrels", sort = ItemSort.SCORE))
                .body!!
                .items
                .firstOrNull()
                ?.item == idOf("alpha", "q1")
        assertTrue(
            found,
            "found ${items.listItems("books", ItemQuery(search = "cider barrels")).body!!.items.map { it.item }}",
        )
    }
}
