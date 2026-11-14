package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardValue
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.ARCHIVE
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.CATEGORY
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.STATUS
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.TAG
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.nodes.Document

/**
 * The fields of a work as its card and its page tell them — its archive, its place in the archive's tree of categories,
 * its rating, its codes — written to the work's item.
 */
internal class AffFields(private val items: ItemStore) {

    /**
     * The archive the work is in: its subdomain, as the site's menu names it; of the original writing, no fan fiction.
     */
    fun archive(id: String, archive: String) {
        items.setFacet(id, ARCHIVE, listOf(FacetValue(archive, AffCodes.ARCHIVES[archive] ?: archive)))
        val kind = if (archive == AffCodes.ORIGINAL) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION
        items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(kind)))
    }

    /**
     * The work's place in the archive's tree, "Harry Potter » Het - Male/Female » Hermione/Voldemort": written whole as
     * its category, and read out of it — the node of the kind of its relationships ([SECTIONS]) parts the fandom before
     * it from the pairing after it. The fandom is the node right before that one, or the last of a place of none: what
     * the nodes before it are, the archive's groups ("+G to L", "DC Verse Comics"), say no more of it. The marks the
     * site sorts its nodes by ("+", "-") are not of their names. The fandom is known by its archive and its name
     * ([fandomKey]): the page of a fandom is its archive's.
     */
    fun located(id: String, written: List<String>) {
        val nodes = written.map { it.trim().trimStart('+', '-').trim() }.filter { it.isNotEmpty() }
        if (nodes.isEmpty()) return
        val path = nodes.joinToString(" » ")
        items.setFacet(id, CATEGORY, listOf(FacetValue(path.lowercase(), path)))
        val section = nodes.indexOfFirst { it.lowercase() in SECTIONS }
        val fandom = if (section < 0) nodes.last() else nodes.getOrNull(section - 1)
        fandom?.let { items.setFacet(id, FANDOM, listOf(FacetValue(fandomKey(Aff.archiveOf(id), it), it))) }
        val pairings = if (section < 0) emptyList() else nodes.drop(section + 1).filter { '/' in it }
        if (pairings.isNotEmpty()) {
            items.setFacet(id, PAIRING, pairings.map(::pairing).distinctBy { it.key })
            items.setTexts(id, mapOf(PAIRINGS_LINE to pairings.joinToString(", ", "Pairing: ")))
        }
        directions(id, listOfNotNull(if (section < 0) null else SECTIONS[nodes[section].lowercase()]), emptyList())
    }

    /** The site's rating on the age rating's scale: its four steps in order, "SFW" either of the two lowest. */
    fun rating(id: String, written: String) {
        AgeRating.write(items, id, RATINGS[written.replace(Regex("\\s+"), " ").trim()].orEmpty())
    }

    /**
     * The work's codes: the status those of it tell, the kinds of relationships the others do, and every other one a
     * tag of the site — named by its short name ([AffCodes.nameOf]), as other sites name such a tag ("Angst", "BDSM"):
     * of one name, the tags of every site are one normalized tag; what the site tells of it in full its description.
     */
    fun codes(id: String, written: List<String>) {
        val codes = written.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val keys = codes.map { it.lowercase() }
        val status =
            when {
                AffCodes.WIP in keys -> Books.Status.IN_PROGRESS
                AffCodes.COMPLETE in keys || AffCodes.ONESHOT in keys -> Books.Status.FINISHED
                else -> null
            }
        status?.let { items.setFacet(id, STATUS, listOf(FacetValue(it.key, it.modelName))) }
        items.setFacet(
            id,
            TAG,
            codes
                .filter { it.lowercase() != AffCodes.WIP && it.lowercase() != AffCodes.COMPLETE }
                .map { FacetValue(it.lowercase(), AffCodes.nameOf(it)) },
        )
        val told = keys.mapNotNull { k -> AffCodes.NAMES[k]?.let { k to it } }.toMap()
        if (told.isNotEmpty()) items.describeFacetValues(TAG, told)
        directions(id, emptyList(), keys)
    }

    /**
     * THE SITE'S OWN LEGEND OF ITS CODES, on a page that shows it — the filters of its advanced search, every one its
     * code and its title "Short - what it means": the name and the description of every code it tells, those of no work
     * kept among them — a code whose works are kept out is named and described all the same. Read of the page the user
     * opened, nothing asked of the site for it.
     */
    fun legend(doc: Document) {
        val told =
            doc.select("label.tag-filter-checkbox[title]")
                .mapNotNull { label ->
                    val code = label.selectFirst("input[value]")?.attr("value")?.trim()?.lowercase()?.ifEmpty { null }
                    val title = label.attr("title").trim().ifEmpty { null }
                    if (code == null || title == null) null else code to title
                }
                // The codes of the status are no tags
                .filter { (code, _) -> code != AffCodes.WIP && code != AffCodes.COMPLETE }
                .toMap()
        if (told.isEmpty()) return
        items.nameFacetValues(TAG, told.mapValues { (code, title) -> AffCodes.shortOf(title) ?: code })
        items.describeFacetValues(TAG, told)
    }

    /**
     * The kinds of the work's relationships, of its category's node ([section]) and of its codes ([codes]) together:
     * each tells some, neither all. Written once both are read: a card tells both.
     */
    private fun directions(id: String, section: List<StandardValue>, codes: List<String>) {
        val known = items.facets(id)[DIRECTION].orEmpty()
        val told = section + codes.mapNotNull { CODED[it] }
        if (told.isEmpty()) return
        val keys = (known + told.map { it.key }).distinct()
        items.setFacet(
            id,
            DIRECTION,
            keys
                .mapNotNull { k -> ALL_DIRECTIONS.firstOrNull { it.key == k } }
                .map { FacetValue(it.key, it.modelName) },
        )
    }

    companion object {
        /** A fandom's key: its archive and its name in the archive's tree, "hp/Harry Potter". */
        fun fandomKey(archive: String, name: String) = "$archive/$name"

        /** The archive of a fandom's key; null of a key of no archive, of a parser before it ("Harry Potter"). */
        fun archiveOfFandom(key: String): String? = key.substringBefore('/', "").takeIf { ARCHIVE_NAME.matches(it) }

        /** An archive's name: its subdomain. */
        private val ARCHIVE_NAME = Regex("[a-z0-9]+")

        /**
         * The nodes of the archives' trees that tell the kind of a work's relationships, or a section of its fandom of
         * no kind (null): what parts the fandom before them from the pairing after them.
         */
        private val SECTIONS: Map<String, StandardValue?> =
            mapOf(
                "het - male/female" to Books.Direction.HET,
                "het" to Books.Direction.HET,
                "slash - male/male" to Books.Direction.SLASH,
                "yaoi - male/male" to Books.Direction.SLASH,
                "slash" to Books.Direction.SLASH,
                "femslash - female/female" to Books.Direction.FEMSLASH,
                "femmeslash - female/female" to Books.Direction.FEMSLASH,
                "femmeslash" to Books.Direction.FEMSLASH,
                "misc - femmeslash" to Books.Direction.FEMSLASH,
                "yuri - female/female" to Books.Direction.FEMSLASH,
                "threesomes/moresomes" to Books.Direction.MIXED,
                "threesomes plus" to Books.Direction.MIXED,
                "general" to null,
                "au - alternate universe" to null,
                "crossovers" to null,
                "misc - crossovers" to null,
                "no category yet" to null,
            )

        /** The codes that tell the kind of a work's relationships. */
        private val CODED =
            mapOf(
                AffCodes.HET to Books.Direction.HET,
                AffCodes.SLASH to Books.Direction.SLASH,
                AffCodes.FEMSLASH to Books.Direction.FEMSLASH,
                AffCodes.MANY to Books.Direction.MIXED,
            )

        private val ALL_DIRECTIONS =
            listOf(Books.Direction.HET, Books.Direction.SLASH, Books.Direction.FEMSLASH, Books.Direction.MIXED)

        /**
         * The site's ratings on the age rating's scale: no definition of them published, its four steps in their order
         * onto its four highest — "SFW", safe for work, either of the two lowest.
         */
        private val RATINGS =
            mapOf(
                "SFW" to AgeRating.between(AgeRating.G, AgeRating.PG13),
                "Adult" to listOf(AgeRating.R),
                "Adult +" to listOf(AgeRating.NC17),
                "Adult ++" to listOf(AgeRating.NC21),
            )

        /** A pairing as written, "Harry/Draco": one whatever the order of its names — the key has them sorted. */
        private fun pairing(written: String): FacetValue =
            FacetValue(
                written.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("/"),
                written,
            )
    }
}
