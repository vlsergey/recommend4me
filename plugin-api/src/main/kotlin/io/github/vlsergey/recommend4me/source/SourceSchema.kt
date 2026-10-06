package io.github.vlsergey.recommend4me.source

/**
 * What a source knows of its items beyond the title, and how the application uses each thing.
 * The labels are shown to the user as they are.
 */
class SourceSchema(
    val facets: List<FacetDef> = emptyList(),
    val numbers: List<NumberDef> = emptyList(),
    val texts: List<TextDef> = emptyList(),
    /** What the source calls the opinions of other people it keeps as reviews ("Отзывы", "Рецензии"); null when it keeps none. */
    val reviewsLabel: String? = null,
    /** What the source calls the parts of a work's text ("Главы"); null when it keeps no text. */
    val partsLabel: String? = null,
    /** Whether an item has a version that changes (a game's release) or is the same work whatever is added to it (a book). */
    val versioned: Boolean = false,
) {
    fun facet(key: String): FacetDef? = facets.firstOrNull { it.key == key }
    fun number(key: String): NumberDef? = numbers.firstOrNull { it.key == key }
    fun text(key: String): TextDef? = texts.firstOrNull { it.key == key }
}

/**
 * A categorical property of items: tags, an engine, a status, a developer, a fandom. An item has
 * any number of values of a facet; a value has a stable key and a name to show (they may be the
 * same).
 */
class FacetDef(
    val key: String,
    val label: String,
    /** The list can be filtered by it: a drop-down of its values. */
    val filter: Boolean = false,
    /** The model reads it: every value seen in at least two rated works is a feature. */
    val feature: Boolean = true,
    /**
     * Facets of different sources of one content type with the same [shared] key are one facet for
     * the model and the filters ("author", "tag"); null — the facet is the source's alone.
     */
    val shared: String? = null,
    /** How much a match of a search in its values says of the item, 0 — not searched. */
    val searchWeight: Float = 0f,
    /** Shown on the card of an item. */
    val onCard: Boolean = false,
    /** The facet names who made the work — the author, the developer: a search for the whole name finds the work first. */
    val names: Boolean = false,
    /** An item without a value of it is listed under this name in the filter (a game with no status is "in development"); null — no such line. */
    val noneLabel: String? = null,
)

/** How the model reads a number. */
enum class NumberScale {
    /** A count spread over orders of magnitude — views, likes: its logarithm. */
    LOG,

    /** A number of a fixed range — a rating of 0..5: as it is. */
    LINEAR,
}

class NumberDef(
    val key: String,
    val label: String,
    val scale: NumberScale = NumberScale.LOG,
    val feature: Boolean = true,
    /** The list can be sorted by it. */
    val sortable: Boolean = false,
    val shared: String? = null,
)

/**
 * A text of an item: an overview, an annotation, a changelog.
 *
 * Every text with a [block] is encoded by the text encoder and its vector is a block of the
 * model's input under that key: two sources' annotations of one content type share
 * `text:annotation`. A text without one is shown and searched only.
 */
class TextDef(
    val key: String,
    val label: String,
    val block: String? = null,
    val searchWeight: Float = 0.5f,
    /** Encoded for the search by meaning even when the model does not read it. */
    val searchByMeaning: Boolean = block != null,
    /** Hidden under a spoiler in the interface until opened. */
    val spoiler: Boolean = false,
)

/** A setting of a source the user can change in the interface: a cookie, a delay between requests. */
class SettingDef(
    val key: String,
    val label: String,
    val kind: SettingKind = SettingKind.TEXT,
    val default: String? = null,
    /** Never sent back to the interface: only whether it is set. */
    val secret: Boolean = false,
    val hint: String? = null,
)

enum class SettingKind { TEXT, NUMBER }
