package io.github.vlsergey.recommend4me.suggestion

/**
 * THE CHANCES OF ONE WORK'S VALUES OF ONE FACET, by value: the few it is shown with — its own, the
 * site's examples, the rejected, the likely and the likeliest others, a dozen or so — in an array, a
 * value looked for by going through them. Of tens of thousands of works and six facets, a table
 * each was half a million tables. Every value once.
 */
class FewChances(private val chances: Array<Chance>) : AbstractMap<String, Chance>() {

    override val size: Int get() = chances.size

    override fun get(key: String): Chance? {
        for (c in chances) if (c.key == key) return c
        return null
    }

    override fun containsKey(key: String): Boolean = get(key) != null

    override val values: Collection<Chance> get() = chances.asList()

    override val entries: Set<Map.Entry<String, Chance>>
        get() = object : AbstractSet<Map.Entry<String, Chance>>() {
            override val size: Int get() = chances.size

            override fun iterator(): Iterator<Map.Entry<String, Chance>> = chances.asSequence().map { Entry(it) }.iterator()
        }

    private class Entry(private val chance: Chance) : Map.Entry<String, Chance> {
        override val key: String get() = chance.key
        override val value: Chance get() = chance

        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()
    }
}
