package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * What every tag of one reading is named: the stats, the user's names, the entries of a catalogue the tags are bound
 * to, the tags above and under them, the tags the user ignores; and the user's [languages], the first first.
 */
class Naming(
    val stats: Map<String, TagStats>,
    val explicit: Map<Long, String>,
    val entries: Map<Long, List<BoundEntry>>,
    val hierarchy: TagHierarchy,
    val ignored: Set<String>,
    private val languages: List<String>,
) {
    fun explicitOf(key: String): String? = TagRepository.idOf(key)?.let(explicit::get)

    fun entriesOf(key: String): List<BoundEntry> = TagRepository.idOf(key)?.let(entries::get).orEmpty()

    /**
     * The label of the tag's entries in the user's first language one of them has it in: of the one bound first, of
     * those that do.
     */
    fun cataloguedOf(key: String): String? {
        val bound = entriesOf(key)
        return languages.firstNotNullOfOrNull { language -> bound.firstNotNullOfOrNull { it.entry.labels[language] } }
    }

    /** The graded works of the tag [key]. */
    fun graded(key: String): Set<ItemKey> = stats[key]?.graded.orEmpty()

    /** The graded works the user said have not the tag [key]. */
    fun rejected(key: String): Set<ItemKey> = stats[key]?.rejected.orEmpty()

    fun nameOf(t: TagStats): ShownName = TagNaming.name(t, explicitOf(t.key), cataloguedOf(t.key))

    fun shown(key: String): String = stats[key]?.let { nameOf(it).name } ?: explicitOf(key) ?: cataloguedOf(key) ?: key

    /** Every name the tag goes by: of its sites' tags, the user's, its entries' in every language. */
    fun allNames(t: TagStats): List<String> =
        t.variants.values.map { it.name } + listOfNotNull(explicitOf(t.key)) + entriesOf(t.key).flatMap { it.names }

    fun named(
        t: TagStats,
        descriptions: SiteDescriptions = SiteDescriptions(),
        offers: List<CatalogueOffer> = emptyList(),
    ) =
        NamedTag(
            t,
            nameOf(t),
            explicitOf(t.key),
            parents = hierarchy.parentsOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            children = hierarchy.childrenOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            entries = entriesOf(t.key),
            descriptions = descriptions.read,
            awaitedDescriptions = descriptions.awaited,
            offers = offers,
            ignored = t.key in ignored,
        )
}
