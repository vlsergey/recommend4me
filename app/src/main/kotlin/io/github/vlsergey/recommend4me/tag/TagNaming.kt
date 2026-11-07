package io.github.vlsergey.recommend4me.tag

/**
 * WHAT A TAG IS SHOWN BY, the user's order: the name the user gave it; else the label of the entry of a catalogue the
 * user bound it to, in the user's first language that has one ([catalogued]: the tag translated); else its name on the
 * sites most used by the works the user liked (graded above the middle of the scale), by the works graded, by all its
 * works; else the first by the alphabet. One order of all its names on the sites ([names]): by the liked works, of
 * those used equally by the graded, then by all, then by the alphabet — its first the name, the interface lists the
 * others after it in the same order.
 */
object TagNaming {
    fun name(t: TagStats, explicit: String?, catalogued: String? = null): ShownName =
        when {
            explicit != null -> ShownName(explicit, NameRule.EXPLICIT)
            catalogued != null -> ShownName(catalogued, NameRule.CATALOGUE)
            else -> bySites(t)
        }

    /** The tag's first name on the sites, with the rule that put it first; of none, its key by the alphabet. */
    private fun bySites(t: TagStats): ShownName {
        val (first, uses) = names(t).firstOrNull() ?: return ShownName(t.key, NameRule.ALPHABET)
        val rule =
            when {
                uses.liked > 0 -> NameRule.LIKED
                uses.graded > 0 -> NameRule.GRADED
                uses.works > 0 -> NameRule.ALL
                else -> NameRule.ALPHABET
            }
        return ShownName(first, rule)
    }

    /** The tag's names on the sites, each as written with its uses of every site together, in the order of the name. */
    fun names(t: TagStats): List<Pair<String, Uses>> {
        val byName = HashMap<String, Uses>()
        t.variants.values.forEach { v ->
            val u = byName.getOrPut(v.name) { Uses() }
            u.works += v.uses.works
            u.graded += v.uses.graded
            u.liked += v.uses.liked
        }
        return byName
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, Uses>> { it.second.liked }
                    .thenByDescending { it.second.graded }
                    .thenByDescending { it.second.works }
                    .thenBy { it.first }
            )
    }
}
