package io.github.vlsergey.recommend4me.tag

/**
 * Another tag offered above or under a tag ([relation]: PARENT, CHILD) by what the entries they are bound to are a kind
 * of in [catalogue]: the entries of the way up, from the lower tag's to the upper's, by their labels — "satire",
 * "humor". Only offered: the user says.
 */
class CatalogueOffer(
    val catalogue: String,
    val tag: String,
    val name: String,
    val relation: TagRelation,
    val path: List<String>,
)

/** One tag under another by their entries: [way] up from the child's entry to the parent's. */
class EntryLink(val child: String, val parent: String, val way: List<TagEntryRef>)

/**
 * WHAT THE ENTRIES OF THE TAGS ARE A KIND OF, of one reading: the tags' entries and what was read up from them
 * ([EntriesAbove]), walked up [EntriesAbove.ABOVE_LEVELS] levels; their labels in the user's first language of
 * [languages] that has one. Read for one operation, kept nowhere.
 */
class CatalogueGraph(private val scope: TagScope, private val n: Naming, private val languages: List<String>) {
    val refsOf: Map<String, List<TagEntryRef>> =
        n.entries.entries
            .filter { it.value.isNotEmpty() }
            .associate { (id, list) -> TagRepository.key(id) to list.map { TagEntryRef(it.catalogue, it.entry.id) } }
    val tagsOf: Map<TagEntryRef, List<String>> =
        HashMap<TagEntryRef, MutableList<String>>().also { m ->
            refsOf.forEach { (key, refs) -> refs.forEach { m.getOrPut(it) { ArrayList() } += key } }
        }
    private val links: Map<TagEntryRef, List<TagEntryRef>> =
        if (refsOf.isEmpty()) emptyMap() else scope.tags.entries.aboveLinks()

    /** Every entry reached up from [starts], with the way to it from one of them, the nearest. */
    fun up(starts: List<TagEntryRef>): Map<TagEntryRef, List<TagEntryRef>> {
        val ways = LinkedHashMap<TagEntryRef, List<TagEntryRef>>()
        starts.forEach { ways[it] = listOf(it) }
        var frontier = starts
        repeat(EntriesAbove.ABOVE_LEVELS) {
            frontier = frontier.flatMap { from ->
                links[from]
                    .orEmpty()
                    .filter { it !in ways }
                    .map { to -> to.also { ways[to] = ways.getValue(from) + to } }
            }
        }
        return ways
    }

    /** Of every tag, the entries reached up from its own. */
    val reached: Map<String, Map<TagEntryRef, List<TagEntryRef>>> by lazy { refsOf.mapValues { (_, refs) -> up(refs) } }

    /**
     * Every tag under another by their entries — one of its entries a kind of one of the other's, the nearest way — but
     * of two related already, by the user's word or through others, and but over the head of a tag between: one whose
     * entry is above the child's and under the parent's, or one the child is under by the user's word whose entry is
     * under the parent's.
     */
    fun links(): List<EntryLink> {
        val upFrom = HashMap<TagEntryRef, Set<TagEntryRef>>()
        fun above(ref: TagEntryRef) = upFrom.getOrPut(ref) { up(listOf(ref)).keys - ref }
        return reached.flatMap { (child, ways) ->
            val related = n.hierarchy.ancestors(child) + n.hierarchy.descendants(child) + child
            // What stands between: the entries of other tags above its own, and the entries of the tags it is under
            // by the user's word
            val between =
                ways.filter { (ref, way) -> way.size > 1 && tagsOf[ref].orEmpty().any { it != child } }.keys +
                    n.hierarchy.ancestors(child).flatMap { refsOf[it].orEmpty() }
            val nearest = LinkedHashMap<String, List<TagEntryRef>>()
            ways.entries
                .filter { it.value.size > 1 }
                .sortedBy { it.value.size }
                .forEach { (ref, way) ->
                    tagsOf[ref]
                        .orEmpty()
                        .filter { it !in related }
                        .forEach { parent ->
                            // Not over the head of a tag between: that one is offered, and above it the parent
                            val over = between.any { b -> parent !in tagsOf[b].orEmpty() && ref in above(b) }
                            if (!over) nearest.putIfAbsent(parent, way)
                        }
                }
            nearest.map { (parent, way) -> EntryLink(child, parent, way) }
        }
    }

    /** The label of every entry of [refs] in the user's first language that has one; else its id. */
    fun labels(refs: Collection<TagEntryRef>): (TagEntryRef) -> String {
        val kept = scope.tags.entries.keptEntries(refs.toSet())
        return { ref ->
            kept[ref]?.labels?.let { labels -> languages.firstNotNullOfOrNull { labels[it] } } ?: ref.entry
        }
    }

    /**
     * The other tags offered above and under [tag] by the catalogues: a tag bound to an entry its entries are a kind
     * of, up to [EntriesAbove.ABOVE_LEVELS] levels — offered above it; a tag whose entries are a kind of its own —
     * under it. Not a tag that is above or under it already, by the user's word or through others: the nearest way of
     * each, a tag once.
     */
    fun offersOf(tag: String): List<CatalogueOffer> {
        val found = links().filter { it.child == tag || it.parent == tag }
        if (found.isEmpty()) return emptyList()
        val label = labels(found.flatMap { it.way })
        return found
            .map { l ->
                val above = l.child == tag
                val other = if (above) l.parent else l.child
                CatalogueOffer(
                    l.way.first().catalogue,
                    other,
                    n.shown(other),
                    if (above) TagRelation.PARENT else TagRelation.CHILD,
                    l.way.map(label),
                )
            }
            .sortedWith(
                compareBy<CatalogueOffer> { it.relation }.thenBy { it.path.size }.thenBy { it.name.lowercase() }
            )
    }
}
