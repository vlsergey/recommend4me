package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.SeriesData
import io.github.vlsergey.recommend4me.source.SeriesWork
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.WantedPage
import io.github.vlsergey.recommend4me.tag.TagCatalogue
import io.github.vlsergey.recommend4me.tag.TagEntry
import io.github.vlsergey.recommend4me.universe.CatalogueLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseCatalogue
import io.github.vlsergey.recommend4me.universe.UniverseCharacter
import io.github.vlsergey.recommend4me.universe.UniverseClass
import io.github.vlsergey.recommend4me.universe.UniverseEntry
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import kotlin.math.sqrt
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The plugins of the site of the tests ([SiteTestBase]): the site's source, a catalogue of universes and tags, and an
 * encoder of texts by their words.
 */
@TestConfiguration
class SitePlugins {
    @Bean fun siteSource(): Source = SiteSource()

    @Bean fun wordsEncoder(): TextEncoder = WordsEncoder()

    @Bean fun fakeCatalogue(): FakeCatalogue = FakeCatalogue()

    /**
     * A catalogue of two universes of space opera, each with the same two characters and a station, a place; its texts
     * in English. And of one concept a tag is bound to: a hull, named in Russian too.
     */
    class FakeCatalogue : UniverseCatalogue, TagCatalogue {
        override val id = "fake"

        private fun en(text: String) = mapOf("en" to text)

        private val hull =
            TagEntry(
                "E1",
                mapOf("en" to "Hull", "ru" to "Корпус"),
                mapOf("en" to listOf("Ship hull")),
                mapOf("en" to "the body of a ship"),
                "https://fake.example/E1",
            )
        private val vessel =
            TagEntry(
                "E2",
                mapOf("en" to "Vessel", "ru" to "Судно"),
                emptyMap(),
                mapOf("en" to "a ship"),
                "https://fake.example/E2",
            )

        override fun find(text: String, languages: List<String>) =
            when {
                "hull" in text.lowercase() || text == "E1" -> listOf(hull)
                "vessel" in text.lowercase() || text == "E2" -> listOf(vessel)
                else -> emptyList()
            }

        private val craft =
            TagEntry("E3", mapOf("en" to "Craft", "ru" to "Аппарат"), emptyMap(), emptyMap(), "https://fake.example/E3")

        private val sail =
            TagEntry("E4", mapOf("en" to "Sail", "ru" to "Парус"), emptyMap(), emptyMap(), "https://fake.example/E4")

        private val keel =
            TagEntry("E6", mapOf("en" to "Keel", "ru" to "Киль"), emptyMap(), emptyMap(), "https://fake.example/E6")

        override fun entries(ids: Collection<String>, languages: List<String>) =
            listOf(hull, vessel, craft, sail, keel).filter { it.id in ids }

        /**
         * A hull is a kind of vessel, a vessel a kind of craft; a sail a kind of craft too; a keel a kind of vessel.
         */
        override fun above(ids: Collection<String>) =
            mapOf("E1" to listOf("E2"), "E2" to listOf("E3"), "E4" to listOf("E3"), "E6" to listOf("E2")).filterKeys {
                it in ids
            }

        private fun entries(languages: List<String>) =
            listOf(
                UniverseEntry("U1", en("Space opera"), en("Ships and pilots"), "https://fake.example/U1", languages),
                UniverseEntry("U2", en("Fleet"), en("More ships"), "https://fake.example/U2", languages),
            )

        override fun findUniverses(name: String, languages: List<String>) =
            if ("space" in name.lowercase()) entries(languages).take(1) else emptyList()

        override fun universe(id: String, languages: List<String>, properties: Set<String>) =
            entries(languages).firstOrNull { it.id == id }

        override fun characters(id: String, languages: List<String>, properties: Set<String>) =
            listOf(
                UniverseCharacter(
                    "C1",
                    en("Pilot"),
                    mapOf("en" to listOf("The Pilot")),
                    emptyMap(),
                    "https://fake.example/C1",
                    languages,
                    listOf("K1"),
                    properties = if ("P1" in properties) mapOf("P1" to listOf("Space Pilot")) else emptyMap(),
                ),
                UniverseCharacter(
                    "C2",
                    en("Captain"),
                    emptyMap(),
                    en("Of the fleet"),
                    "https://fake.example/C2",
                    languages,
                    listOf("K1"),
                ),
                UniverseCharacter(
                    "S1",
                    en("Station"),
                    emptyMap(),
                    en("Where ships dock"),
                    "https://fake.example/S1",
                    languages,
                    listOf("K2"),
                ),
            )

        override fun classes(ids: Collection<String>, languages: List<String>) =
            listOf(
                    UniverseClass("K1", en("person"), true, languages),
                    UniverseClass("K2", en("place"), false, languages),
                )
                .filter { it.id in ids }
    }

    /**
     * A site whose pages are "<id>|<title>|<tags>|<annotation>|<pairings>": tags to suggest, and pairings the
     * application works out from the line the site writes them in. What a tag means it answers at [describe] of the
     * tag.
     */
    class SiteSource : Source {
        companion object {
            const val DESCRIBE = "https://site.example/tags/"

            fun describe(tag: String) = "$DESCRIBE$tag"

            /** A page of a tag's other names: "<name>,<name>", the tag in the address. */
            const val SYNONYMS = "https://site.example/synonyms/"

            /**
             * A series' page: "<title>|<description>|<id>,<id>", its every work in order, the series in the address.
             */
            const val SERIES = "https://site.example/series/"
        }

        override val id = "site"
        override val contentType = "books"
        override val homepage = "https://site.example"

        /** A character's tag on the site, as the catalogue states it. */
        override val catalogueProperties = mapOf("fake" to setOf("P1"))
        override val schema =
            SourceSchema(
                facets =
                    listOf(
                        // The site's tags: its word, the examples of the work's tags
                        Books.TAG.asFacet(),
                        FacetDef("pairing", infer = true, original = "pairings"),
                        FacetDef("series", role = FacetRole.SERIES),
                    ),
                texts =
                    listOf(
                        TextDef("annotation", block = "text:annotation"),
                        TextDef("pairings", searchByMeaning = true),
                    ),
                universeLine = "pairings",
            )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://site\\.example/(work|tags|synonyms|series)/.*"))

        override fun itemUrl(itemId: String) = "https://site.example/work/$itemId"

        override fun itemIdOf(url: String) =
            Regex("^https://site\\.example/work/([^/?#]+)").find(url)?.groupValues?.get(1)

        override val pageDecor =
            PageDecor(
                panelAfter = "h1",
                facets = listOf(FacetDecor(Books.TAGS.key, after = ".tags")),
                cards = CardDecor(".card", "a.title"),
            )

        /** What a tag means, asked of the site by its address of the tag: the answer is the description itself. */
        override fun describing(facet: String, tags: List<String>): List<WantedPage> =
            if (facet != "tag") emptyList() else tags.map { WantedPage(describe(it), mapOf("tag" to it)) }

        override fun capture(page: CapturedPage, context: SourceContext): List<String> =
            when {
                page.url.startsWith(SiteSource.SYNONYMS) -> {
                    synonyms(page, context)
                    emptyList()
                }
                page.url.startsWith(SiteSource.DESCRIBE) -> {
                    description(page, context)
                    emptyList()
                }
                page.url.startsWith(SiteSource.SERIES) -> series(page, context)
                else -> listOf(work(page, context))
            }

        /** A series' page: the series and its every work in order; the works. */
        private fun series(page: CapturedPage, context: SourceContext): List<String> {
            val (title, description, ids) = page.html.split('|')
            val works = ids.split(',').filter { it.isNotBlank() }
            context.items.keepSeries(
                SeriesData(
                    page.url.removePrefix(SiteSource.SERIES),
                    page.url,
                    title,
                    description,
                    works = works.map { SeriesWork(it) },
                    whole = true,
                )
            )
            return works
        }

        /** A page of a tag's other names: each a tag of its own of the same thing. */
        private fun synonyms(page: CapturedPage, context: SourceContext) {
            val names = page.html.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            context.items.setFacetValueSynonyms(
                "tag",
                mapOf(page.url.removePrefix(SiteSource.SYNONYMS) to names.associateBy { it.lowercase() }),
            )
        }

        /** The site's answer of what a tag means: the description itself, none when blank. */
        private fun description(page: CapturedPage, context: SourceContext) {
            page.html
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let {
                    context.items.describeFacetValues(
                        "tag",
                        mapOf(page.url.removePrefix(SiteSource.DESCRIBE) to it),
                    )
                }
        }

        /** A work's page, written; its id. */
        private fun work(page: CapturedPage, context: SourceContext): String {
            val parts = page.html.split('|')
            val (id, title, tags) = parts
            val annotation = parts[3]
            val pairings = parts[4]
            context.items.upsert(
                ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")),
                page.capturedAt,
            )
            context.items.setFacet(
                id,
                "tag",
                tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it.replaceFirstChar(Char::uppercase)) },
            )
            parts.getOrNull(5)?.let { context.items.setFacet(id, "series", listOf(FacetValue(it.lowercase(), it))) }
            context.items.setFacet(
                id,
                "pairing",
                pairings.split(", ").filter { it.isNotBlank() }.map { FacetValue(it.lowercase(), it) },
            )
            context.items.setTexts(id, mapOf("annotation" to annotation, "pairings" to pairings))
            return id
        }

        /**
         * Of a line of friends ("Space Pilot & Captain"), every name the catalogue states as its tag of one character
         * alone: a main character.
         */
        override fun draft(facets: Map<String, List<FacetValue>>, lookup: CatalogueLookup): List<LayerDraft> =
            facets["pairing"]
                .orEmpty()
                .mapNotNull { it.name }
                .filter { " & " in it }
                .flatMap { line ->
                    line.split(" & ").mapNotNull { name ->
                        lookup.characters("fake", "P1", name).singleOrNull()?.let {
                            LayerDraft(UniverseFacets.CHARACTERS, it, name)
                        }
                    }
                }
    }

    /** Texts as bags of their words, hashed into a few dimensions: alike words, alike vectors. */
    class WordsEncoder : TextEncoder {
        override val id = "words"
        override val dim = 512

        override fun ready() = true

        override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> = texts.map { text ->
            val v = FloatArray(dim)
            Regex("\\p{L}+").findAll(text.lowercase()).forEach { w ->
                v[Math.floorMod(w.value.take(5).hashCode(), dim)] += 1f
            }
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            if (n == 0f) null else FloatArray(dim) { v[it] / n }
        }

        override fun encodeWindows(texts: List<String>): List<List<FloatArray>> =
            encode(texts).map { listOfNotNull(it) }

        override fun windowSpans(text: String): List<IntRange> =
            if (text.isBlank()) emptyList() else listOf(text.indices)
    }
}
