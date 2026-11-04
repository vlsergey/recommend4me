package io.github.vlsergey.recommend4me.item

/**
 * ONE WORK'S VALUES OF EVERY FACET, by facet: a dozen facets or so in two arrays, a facet looked for
 * by going through them. Of tens of thousands of works a table each, of a dozen lists each, was a
 * reading of its own of the memory. Every facet once.
 */
class WorkFacets(private val facets: Array<String>, private val lists: Array<List<String>>) : AbstractMap<String, List<String>>() {

    init {
        require(facets.size == lists.size) { "${facets.size} facets of ${lists.size} lists" }
    }

    override val size: Int get() = facets.size

    override fun get(key: String): List<String>? {
        for (k in facets.indices) if (facets[k] == key) return lists[k]
        return null
    }

    override fun containsKey(key: String): Boolean = get(key) != null

    override val keys: Set<String>
        get() = object : AbstractSet<String>() {
            override val size: Int get() = facets.size

            override fun iterator(): Iterator<String> = facets.iterator()
        }

    override val values: Collection<List<String>> get() = lists.asList()

    override val entries: Set<Map.Entry<String, List<String>>>
        get() = object : AbstractSet<Map.Entry<String, List<String>>>() {
            override val size: Int get() = facets.size

            override fun iterator(): Iterator<Map.Entry<String, List<String>>> = facets.indices.asSequence().map { Entry(facets[it], lists[it]) }.iterator()
        }

    private class Entry(override val key: String, override val value: List<String>) : Map.Entry<String, List<String>> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()
    }

    /**
     * EVERY WORK'S VALUES OF A SOURCE READ ROW BY ROW, in any order, into [WorkFacets]: every
     * facet's name and every value's key one string of the reading however many works have it — of a
     * database row, a string of its own each.
     */
    class Reading {
        private val strings = HashMap<String, String>()
        private val works = HashMap<String, Building>()

        private class Building {
            val facets = ArrayList<String>(4)
            val lists = ArrayList<ArrayList<String>>(4)
        }

        private fun one(s: String): String = strings.putIfAbsent(s, s) ?: s

        fun add(itemId: String, facet: String, key: String) {
            val work = works.getOrPut(itemId) { Building() }
            val f = one(facet)
            var at = work.facets.indexOf(f)
            if (at < 0) {
                work.facets += f
                work.lists.add(ArrayList(2))
                at = work.facets.size - 1
            }
            work.lists[at] += one(key)
        }

        /** Every work read, by its id. */
        fun done(): Map<String, WorkFacets> {
            val out = HashMap<String, WorkFacets>(works.size * 4 / 3 + 1)
            works.forEach { (id, w) -> out[id] = WorkFacets(w.facets.toTypedArray(), Array(w.lists.size) { w.lists[it].toTypedArray().asList() }) }
            return out
        }
    }
}
