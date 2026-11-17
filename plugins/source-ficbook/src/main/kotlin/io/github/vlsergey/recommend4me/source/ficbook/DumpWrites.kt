package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.BASE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.LIKES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ONLY_PART
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.TAG
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant

/** A work as the dump has it ([FicbookDump]): the site's words of it as they were when it was collected. */
internal class DumpWork(
    val url: String,
    val title: String,
    /** The authors by their names alone. */
    val authors: List<String>,
    val tags: List<String>,
    val description: String?,
    val likes: Long?,
    val partCount: Long?,
    /** The age rating as the site writes it, "PG-13". */
    val rating: String?,
    /** "Завершён", "В процессе", "Заморожен". */
    val status: String?,
    /** "Джен", "Гет", "Слэш"… */
    val direction: String?,
    /** The fandom by its name alone, "Роулинг Джоан «Гарри Поттер»"; "Ориджиналы" of an original work. */
    val fandom: String?,
    /** The line of pairings and characters the author wrote. */
    val pairing: String?,
    val parts: List<DumpPart>,
)

/** A part of a work of the dump: its own page's address, its title, its text, the day it was published. */
internal class DumpPart(val url: String, val title: String?, val text: String?, val publishedAt: Instant?)

/**
 * A WORK OF THE DUMP WRITTEN AS THE SITE'S PAGE WOULD WRITE IT — WHAT THE APPLICATION HAS OF IT WINS (user,
 * 2026-11-17): a work known already gets the texts of the parts it lacks alone, its own head, values and texts kept; a
 * work new everything the dump says of it:
 * - its authors and its fandom by their names: the site's own key of a name known already, else the name itself under
 *   [NAME_KEY] — the site's page gives the key later, the name's key then of no work, bound with it by the application;
 * - "Ориджиналы" the fandom of the original works, the kind of the work by it;
 * - the direction, the age rating and the status mapped onto the standard ones, as a page's labels;
 * - the tags by their names, as a page's;
 * - the line of pairings and characters kept as a text whole, never parsed: the authors wrote it as they pleased;
 * - every part with its text, the work updated as its latest part.
 */
internal class DumpWrites(private val items: ItemStore, private val itemIdOf: (String) -> String?) {
    /** The site's own keys of the authors and the fandoms by their names, as far as known before the import. */
    private val authors = keysByName(AUTHOR)
    private val fandoms = keysByName(FANDOM)

    /** The work written; whether it is new. A work whose address is no work's is left out. */
    fun write(work: DumpWork): Boolean {
        val id = itemIdOf(work.url)
        return when {
            id == null -> false
            items.find(id) != null -> {
                fillParts(id, work)
                false
            }
            else -> {
                writeNew(id, work)
                true
            }
        }
    }

    /** Of a work known, the texts of the parts it lacks: a part known keeps its place. */
    private fun fillParts(id: String, work: DumpWork) {
        val kept = items.parts(id)
        val lacking =
            parts(work).filter { kept[it.id] != true }.map { if (it.id in kept) it.copy(position = -1) else it }
        if (lacking.isNotEmpty()) items.saveParts(id, lacking)
    }

    private fun writeNew(id: String, work: DumpWork) {
        val parts = parts(work)
        items.upsert(
            ItemHead(id, "$BASE/readfic/$id", work.title, updatedAt = parts.mapNotNull { it.publishedAt }.maxOrNull()),
            Instant.now(),
        )
        values(id, work)
        items.setNumbers(id, mapOf(LIKES to work.likes?.toDouble(), PARTS to work.partCount?.toDouble()))
        val texts =
            mapOf(ANNOTATION to work.description, PAIRINGS_LINE to work.pairing).filterValues { !it.isNullOrBlank() }
        if (texts.isNotEmpty()) items.setTexts(id, texts)
        if (parts.isNotEmpty()) items.saveParts(id, parts)
    }

    private fun values(id: String, work: DumpWork) {
        if (work.authors.isNotEmpty())
            items.setFacet(id, AUTHOR, work.authors.distinct().map { FacetValue(keyOf(authors, it), it) })
        work.fandom
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { name ->
                val key = if (name == ORIGINALS_NAME) FicbookFields.ORIGINALS else keyOf(fandoms, name)
                items.setFacet(id, FANDOM, listOf(FacetValue(key, name)))
                val kind = if (key == FicbookFields.ORIGINALS) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION
                items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(kind)))
            }
        Books.DIRECTION.values
            ?.firstOrNull { it.modelName.equals(work.direction?.trim(), ignoreCase = true) }
            ?.let { items.setFacet(id, DIRECTION, listOf(FacetValue(it.key, it.modelName))) }
        work.rating?.let { r ->
            AgeRating.write(items, id, listOfNotNull(AgeRating.VALUES.firstOrNull { it.key == r.trim().lowercase() }))
        }
        work.status
            ?.let { FicbookFields.STATUSES[it.trim().lowercase()] }
            ?.let { items.setFacet(id, STATUS, listOf(FacetValue(it.key, it.modelName))) }
        if (work.tags.isNotEmpty())
            items.setFacet(id, TAG, work.tags.map { FacetValue(it.lowercase(), it) }.distinctBy { it.key })
    }

    /** The parts in the order of the dump: of a work of one part, its text the work's own page's ([ONLY_PART]). */
    private fun parts(work: DumpWork): List<PartData> =
        work.parts.mapIndexed { i, p ->
            PartData(PART_ID.find(p.url)?.groupValues?.get(1) ?: ONLY_PART, i, p.title, p.text, p.publishedAt)
        }

    private fun keysByName(facet: String): Map<String, String> =
        items
            .facetNames(facet)
            .entries
            .filterNot { it.key.startsWith(NAME_KEY) }
            .groupBy({ it.value.trim() }, { it.key })
            .mapValues { (_, keys) -> keys.min() }

    private fun keyOf(known: Map<String, String>, name: String): String = known[name] ?: (NAME_KEY + name)

    companion object {
        /**
         * The key of an author or a fandom the dump gives by the name alone, the site's own unknown: "name:" before the
         * name. No page of the site has it: no address is made of it ([Ficbook.valueUrl]).
         */
        const val NAME_KEY = "name:"

        /** The fandom of the original works, by the name the dump gives it. */
        private const val ORIGINALS_NAME = "Ориджиналы"

        /** A part's own page: the part's id; a work of one part has its text on the work's page. */
        private val PART_ID = Regex("/readfic/[^/?#]+/([^/?#]+)")
    }
}
