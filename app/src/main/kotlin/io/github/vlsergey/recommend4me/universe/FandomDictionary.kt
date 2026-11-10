package io.github.vlsergey.recommend4me.universe

/**
 * THE NORMALIZED FANDOMS AS ONE OPERATION READS THEM ([load]), handed to every part of it and never kept past it: every
 * fandom, the sites' fandoms bound to each, the entries of the catalogue each is bound to, and one under another.
 */
class FandomDictionary
private constructor(
    /** Every normalized fandom by its id. */
    val fandoms: Map<Long, FandomRow>,
    private val bindings: Map<SiteFandomRef, Long>,
    private val entries: Map<Long, List<UniverseKey>>,
    private val parents: Map<Long, Set<Long>>,
) {
    /** Every fandom as a value of the works' universes. */
    val values: Set<String> by lazy { fandoms.keys.map(FandomRepository::value).toSet() }

    private val ofEntry: Map<String, Set<Long>> by lazy {
        val out = HashMap<String, MutableSet<Long>>()
        entries.forEach { (id, keys) -> keys.forEach { out.getOrPut(it.value) { HashSet() } += id } }
        out
    }

    private val sitesOf: Map<Long, List<SiteFandomRef>> by lazy { bindings.entries.groupBy({ it.value }, { it.key }) }

    /** The normalized fandom the site's fandom [key] of [source] is bound to; null of one not bound yet. */
    fun ofSite(source: String, key: String): Long? = bindings[SiteFandomRef(source, key)]

    /** The sites' fandoms bound to the fandom [id]. */
    fun sitesOf(id: Long): List<SiteFandomRef> = sitesOf[id].orEmpty()

    /** The entries the fandom [id] is bound to, in the order bound. */
    fun entriesOf(id: Long): List<UniverseKey> = entries[id].orEmpty()

    /**
     * The entries whose characters the works of the universe [value] may have: of a fandom whose characters are read
     * ([FandomRow.characters]), every entry of it; none of another, or of a value that is no fandom.
     */
    fun characterEntries(value: String): List<UniverseKey> {
        val id = FandomRepository.idOf(value) ?: return emptyList()
        return if (fandoms[id]?.characters == true) entriesOf(id) else emptyList()
    }

    /** Every fandom whose characters are read, as a value, with its entries. */
    fun withCharacters(): Map<String, List<UniverseKey>> =
        fandoms.values.filter { it.characters }.associate { FandomRepository.value(it.id) to entriesOf(it.id) }

    /** The fandoms bound to the entry of the value [entry] ("wikidata:Q5410773"), as values. */
    fun ofEntry(entry: String): Set<String> = ofEntry[entry].orEmpty().map(FandomRepository::value).toSet()

    /**
     * The universes every character is of, of its entries' ([UniverseRepository.characterEntries]: character value to
     * entry values): the fandoms bound to them whose characters are read. A character of no such fandom: none.
     */
    fun universesOfCharacters(characterEntries: Map<String, Set<String>>): Map<String, Set<String>> {
        val read = fandoms.values.filter { it.characters }.map { it.id }.toSet()
        return characterEntries
            .mapValues { (_, of) ->
                of.flatMap { e -> ofEntry[e].orEmpty().filter { it in read } }.map(FandomRepository::value).toSet()
            }
            .filterValues { it.isNotEmpty() }
    }

    /** The parents of the fandom [id]. */
    fun parentsOf(id: Long): Set<Long> = parents[id].orEmpty()

    /** The children of the fandom [id]. */
    fun childrenOf(id: Long): Set<Long> = parents.filterValues { id in it }.keys

    /** Every fandom above [id], its parents' parents too; never itself. */
    fun ancestors(id: Long): Set<Long> {
        val out = LinkedHashSet<Long>()
        val queue = ArrayDeque(parentsOf(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next != id && out.add(next)) queue += parentsOf(next)
        }
        return out
    }

    /** Every fandom below [id], its children's children too; never itself. */
    fun descendants(id: Long): Set<Long> {
        val children = HashMap<Long, MutableSet<Long>>()
        parents.forEach { (child, of) -> of.forEach { children.getOrPut(it) { HashSet() } += child } }
        val out = LinkedHashSet<Long>()
        val queue = ArrayDeque(children[id].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next != id && out.add(next)) queue += children[next].orEmpty()
        }
        return out
    }

    companion object {
        fun load(fandoms: FandomRepository): FandomDictionary =
            FandomDictionary(
                fandoms.all().associateBy { it.id },
                fandoms.sites.bindings(),
                fandoms.entries.all(),
                fandoms.relations.parents(),
            )
    }
}
