package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.AuthorKin
import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.PairCell
import io.github.vlsergey.recommend4me.api.model.PairMeasure
import io.github.vlsergey.recommend4me.api.model.PairRow

/**
 * THE TWO WORKS OF A PAIR FIELD BY FIELD, as the window of a pair draws them (owner, 2026-11-20: the window driven by
 * the server, so a layer the search compares is never shown "not compared"): a row a field — the site, the title, the
 * authors, every facet either has with the sites' names of the fandoms after the universes, the sites' own tags after
 * the tags and the sites' lines of the characters after the facets made of them, the size, the description —, of each
 * the two works' values and how alike they are by every measure the search takes of it ([PairMeasure]), and its group:
 * compared, both having it but the search not comparing it, or of one work alone. Of a pair the search measured
 * ([layers]) its measures; one it did not take of this pair — measured before the search took it, or of a text not
 * encoded yet — "not measured"; of a pair never measured, its fields as they are.
 */
internal class PairFields(
    private val first: Described,
    private val second: Described,
    private val layers: DuplicateCandidate?,
    private val sameAuthor: Boolean,
    private val kin: AuthorKin?,
) {
    private val a = first.fields
    private val b = second.fields

    /** The rows, in the order they are read: of a field neither work has, none. */
    fun rows(): List<PairRow> =
        listOfNotNull(
            row(SITE, PairRow.Show.SITE, PairCell(), PairCell(), listOf(siteMeasure())),
            row(TITLE, PairRow.Show.TITLE, PairCell(), PairCell(), title()),
            row(AUTHORS, PairRow.Show.VALUES, named(a.authors), named(b.authors), authors()),
        ) +
            facetRows() +
            listOfNotNull(
                row(SIZE, PairRow.Show.SIZE, sized(a), sized(b), listOf(size())),
                row(
                    DESCRIPTION,
                    PairRow.Show.TEXT,
                    text(a.description),
                    text(b.description),
                    texts(LetteredText.DESCRIPTION, a.description, b.description) + meaning(layers?.description),
                    DESCRIPTION_LINES,
                ),
            )

    /**
     * The facets in the order the user decides by, the sites' fandoms after the universes, the sites' tags after the
     * tags — in their place, when the tags are not compared either: the two rows would tell the same —, the sites'
     * lines of the characters after the facets made of them.
     */
    private fun facetRows(): List<PairRow> {
        val keys = (a.facets + b.facets).map { it.key }.distinct().filter { it != AUTHOR_FAMILY }
        val rows =
            (FACET_ORDER.filter { it in keys } + keys.filter { it !in FACET_ORDER })
                .mapNotNull(::facetRow)
                .toMutableList()
        val fandoms =
            row(
                SITE_FANDOMS,
                PairRow.Show.VALUES,
                named(a.fandoms),
                named(b.fandoms),
                texts(LetteredText.FANDOMS, a.fandoms.joinToString(), b.fandoms.joinToString()),
                FACET_LINES,
            )
        fandoms?.let { rows.add(rows.indexOfFirst { it.key == UNIVERSE } + 1, it) }
        siteTags()?.let { tags ->
            val at = rows.indexOfFirst { it.key == TAGS }
            val replaced = at >= 0 && rows[at].group == PairRow.Group.NOT_COMPARED
            if (replaced) rows[at] = tags else rows.add(if (at < 0) rows.size else at + 1, tags)
        }
        val line =
            row(
                UNIVERSE_LINE,
                PairRow.Show.TEXT,
                text(a.universeLine),
                text(b.universeLine),
                texts(LetteredText.CHARACTERS, a.universeLine, b.universeLine),
                FACET_LINES,
            )
        line?.let { rows.add(rows.indexOfLast { it.key in OF_THE_LINE } + 1, it) }
        return rows
    }

    /** The row of a facet: none of one neither has. */
    private fun facetRow(key: String): PairRow? {
        val x = a.facets.firstOrNull { it.key == key }
        val y = b.facets.firstOrNull { it.key == key }
        val labelKey = if (key == CHARACTERS) CHARACTERS_LABEL else (x ?: y)!!.labelKey
        return row(
            key,
            labelKey,
            PairRow.Show.VALUES,
            x?.let { cell(it.values) },
            y?.let { cell(it.values) },
            facetMeasures(key, x, y),
            FACET_LINES,
        )
    }

    /**
     * How alike the values of a facet are: none of a facet the search does not compare ([ShownFacet.compared]); of the
     * values a work may have one of, whether they meet; of a facet of one value, whether it is the same; of the values
     * as the works' screens give them, or of a pair never measured, how many they share; else the cosine the search
     * measured — not measured of a pair it measured when either had none.
     */
    private fun facetMeasures(key: String, x: ShownFacet?, y: ShownFacet?): List<PairMeasure>? {
        val both = listOfNotNull(x, y)
        val left = x?.values.orEmpty().map(::nameOf).toSet()
        val right = y?.values.orEmpty().map(::nameOf).toSet()
        val common = left.count { it in right }
        val counted = { kind: PairMeasure.Kind ->
            PairMeasure(kind, common = common, first = left.size, second = right.size)
        }
        val measure =
            when {
                !both.all { it.compared } -> null
                both.any { it.allowed } -> counted(PairMeasure.Kind.ALLOWED)
                both.any { it.single } ->
                    PairMeasure(if (common > 0) PairMeasure.Kind.SAME_VALUE else PairMeasure.Kind.OTHER_VALUE)
                layers == null || both.any { it.computed } -> counted(PairMeasure.Kind.VALUES)
                else ->
                    layers.facets[key]?.let { PairMeasure(PairMeasure.Kind.FACET, value = it.toBigDecimal()) }
                        ?: NOT_MEASURED
            }
        return measure?.let(::listOf)
    }

    /** The sites' own tags: shown, never compared — the normalized tags are. */
    private fun siteTags(): PairRow? =
        if (a.siteTags.isNullOrEmpty() && b.siteTags.isNullOrEmpty()) null
        else row(SITE_TAGS, PairRow.Show.VALUES, a.siteTags?.let(::cell), b.siteTags?.let(::cell), null, FACET_LINES)

    /** The title letter by letter, then by meaning; of a pair never measured, whether they are the same. */
    private fun title(): List<PairMeasure> =
        layers?.let { listOf(letters(it.title)) + meaning(it.titleVector) } ?: same(first.work.title, second.work.title)

    /**
     * The authors: one by the dictionary of authors — a normalized author of both, or only two the user said are partly
     * one —, else of the window of one pair how close their closest two are by their works ([kin]), else others; then
     * their names letter by letter.
     */
    private fun authors(): List<PairMeasure> {
        val partly = sameAuthor && first.authors.none { it in second.authors }
        val one =
            when {
                partly -> PairMeasure(PairMeasure.Kind.PARTLY_AUTHOR)
                sameAuthor -> PairMeasure(PairMeasure.Kind.SAME_AUTHOR)
                kin != null -> PairMeasure(PairMeasure.Kind.AUTHOR_KIN, kin = kin)
                else -> PairMeasure(PairMeasure.Kind.OTHER_AUTHOR)
            }
        return listOf(one) + listOfNotNull(layers?.authors?.let { letters(it.alike) })
    }

    /** A text compared letter by letter by the search; of a pair never measured, whether they are the same. */
    private fun texts(text: LetteredText, x: String?, y: String?): List<PairMeasure> =
        layers?.let { listOf(it.letters[text]?.let(::letters) ?: NOT_MEASURED) } ?: same(x, y)

    /** A text compared by meaning by the search: none of a pair never measured. */
    private fun meaning(cosine: Float?): List<PairMeasure> =
        if (layers == null) emptyList()
        else listOf(cosine?.let { PairMeasure(PairMeasure.Kind.MEANING, value = it.toBigDecimal()) } ?: NOT_MEASURED)

    /** The sizes: of every number both have the smaller over the larger, by the search or by the works' numbers. */
    private fun size(): PairMeasure {
        val ratio =
            layers?.size?.toDouble() ?: ratio(a.words?.value, b.words?.value) ?: ratio(a.chars?.value, b.chars?.value)
        return ratio?.let { PairMeasure(PairMeasure.Kind.SIZE, value = it.toBigDecimal()) } ?: NOT_MEASURED
    }

    private fun siteMeasure() =
        PairMeasure(
            if (first.work.source == second.work.source) PairMeasure.Kind.SAME_SITE else PairMeasure.Kind.OTHER_SITE
        )

    companion object {
        /** The facets compared in the order the user decides by: what the work is of first, the tags last. */
        private val FACET_ORDER =
            listOf(
                "universe",
                "characters",
                "pairings",
                "kind",
                "status",
                "languages",
                "direction",
                "age-rating",
                "age-rating-site",
                "tags",
            )
        private const val UNIVERSE = "universe"
        private const val CHARACTERS = "characters"
        private const val TAGS = "tags"
        private const val SITE = "site"
        private const val TITLE = "title"
        private const val AUTHORS = "authors"
        private const val SIZE = "size"
        private const val DESCRIPTION = "description"
        private const val SITE_FANDOMS = "siteFandoms"
        private const val SITE_TAGS = "siteTags"
        private const val UNIVERSE_LINE = "universeLine"

        /** The facets made of the site's line of the characters and pairings: the line stands after them. */
        private val OF_THE_LINE = setOf(UNIVERSE, SITE_FANDOMS, CHARACTERS, "pairings")

        /** The lines a value is folded to: a description four, the values of a facet and a line two. */
        private const val DESCRIPTION_LINES = 4
        private const val FACET_LINES = 2

        /** The words of the characters' row: named apart from the site's line of them. */
        private val CHARACTERS_LABEL = labelOf(CHARACTERS)

        /** The words of every row of the application's own the server sends: every one among the interface's words. */
        internal val LABELS =
            listOf(SITE, TITLE, AUTHORS, SIZE, DESCRIPTION, SITE_FANDOMS, SITE_TAGS, UNIVERSE_LINE, CHARACTERS)
                .map(::labelOf)

        private val NOT_MEASURED = PairMeasure(PairMeasure.Kind.NOT_MEASURED)
    }
}

/**
 * A row of the field [key], named by [labelKey] (of a field of the application's own, "duplicate:PairGrid.<key>"), its
 * two works' cells — none of a work without it —, compared by [measures] (none: both have it, the search compares it
 * not), folded to [folded] lines: none of a field neither has.
 */
private fun row(
    key: String,
    show: PairRow.Show,
    x: PairCell?,
    y: PairCell?,
    measures: List<PairMeasure>?,
    folded: Int? = null,
) = row(key, labelOf(key), show, x, y, measures, folded)

/** The words of a row of the application's own: "duplicate:PairGrid.<key>". */
private fun labelOf(key: String) = "duplicate:PairGrid.$key"

private fun row(
    key: String,
    labelKey: String,
    show: PairRow.Show,
    x: PairCell?,
    y: PairCell?,
    measures: List<PairMeasure>?,
    folded: Int?,
): PairRow? {
    val group =
        when {
            x == null && y == null -> return null
            x == null || y == null -> PairRow.Group.ONE_SIDED
            measures == null -> PairRow.Group.NOT_COMPARED
            else -> PairRow.Group.COMPARED
        }
    return PairRow(
        key = key,
        labelKey = labelKey,
        show = show,
        group = group,
        measures = if (group == PairRow.Group.COMPARED) measures!! else emptyList(),
        first = x,
        second = y,
        folded = folded,
    )
}

/** The cell of some values; none of none. */
private fun cell(values: List<DuplicateValue>): PairCell? =
    values.takeIf { it.isNotEmpty() }?.let { PairCell(propertyValues = it) }

/** The cell of some names; none of none. */
private fun named(names: List<String>): PairCell? = cell(names.map { DuplicateValue(it, null) })

/** The cell of a text; none of none. */
private fun text(text: String?): PairCell? = text?.takeIf { it.isNotBlank() }?.let { PairCell(text = it) }

/** The cell of a work's size; none of a work of no number of it. */
private fun sized(f: ShownFields): PairCell? =
    if (f.words == null && f.chars == null) null else PairCell(words = f.words, chars = f.chars)

/** What two values are told alike by: the key of the name of an application's own, else the name. */
private fun nameOf(v: DuplicateValue) = v.nameKey ?: v.name

private fun letters(cosine: Float) = PairMeasure(PairMeasure.Kind.LETTERS, value = cosine.toBigDecimal())

/** Two texts the same word for word, ignoring the case and the spaces about them; else not measured. */
private fun same(x: String?, y: String?): List<PairMeasure> =
    listOf(
        if (x != null && y != null && x.trim().equals(y.trim(), ignoreCase = true))
            PairMeasure(PairMeasure.Kind.SAME_TEXT)
        else PairMeasure(PairMeasure.Kind.NOT_MEASURED)
    )

/** The smaller of two numbers over the larger; none when either is not known. */
private fun ratio(x: Double?, y: Double?): Double? =
    if (x == null || y == null || maxOf(x, y) <= 0) null else minOf(x, y) / maxOf(x, y)
