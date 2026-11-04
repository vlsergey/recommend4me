package io.github.vlsergey.recommend4me.suggestion

/**
 * HOW OFTEN A WORK'S WORDS NAME EACH VALUE, by the value's place in the task ([SuggestionTask.mentions]):
 * in two arrays sorted by the place, a value looked for by halving — a dozen values a work, of tens
 * of thousands of works, not a table each. Every place once.
 */
class NameCounts private constructor(private val places: IntArray, private val counts: IntArray) : AbstractMap<Int, Int>() {

    override val size: Int get() = places.size

    override fun get(key: Int): Int? = places.binarySearch(key).let { if (it >= 0) counts[it] else null }

    override fun containsKey(key: Int): Boolean = places.binarySearch(key) >= 0

    override val entries: Set<Map.Entry<Int, Int>>
        get() = object : AbstractSet<Map.Entry<Int, Int>>() {
            override val size: Int get() = places.size

            override fun iterator(): Iterator<Map.Entry<Int, Int>> = places.indices.asSequence().map { Entry(places[it], counts[it]) }.iterator()
        }

    private class Entry(override val key: Int, override val value: Int) : Map.Entry<Int, Int> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value

        override fun hashCode(): Int = key xor value
    }

    companion object {
        /** The counts of [byKey] (a value's key to its count) of the values that have a place in [index]; a value of none left out. */
        fun of(byKey: Map<String, Int>, index: Map<String, Int>): NameCounts {
            val places = IntArray(byKey.size)
            val counts = IntArray(byKey.size)
            var n = 0
            byKey.forEach { (key, count) ->
                val place = index[key] ?: return@forEach
                places[n] = place
                counts[n++] = count
            }
            val order = (0 until n).sortedBy { places[it] }
            return NameCounts(IntArray(n) { places[order[it]] }, IntArray(n) { counts[order[it]] })
        }
    }
}
