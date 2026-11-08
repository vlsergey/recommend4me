package io.github.vlsergey.recommend4me.cast

/**
 * WHAT A SITE'S LINE OF CHARACTERS AND PAIRINGS SAYS OF A WORK, SPLIT AS THE SITE'S AUTHORS USUALLY WRITE IT —
 * ficbook's "Гарри Поттер/Драко Малфой, Гермиона Грейнджер": the entries apart by commas, the names of a pairing by
 * "/". A half structure, with no guarantee: the authors write as they please — a "name" may be "Все", "ОЖП" or "Гарри
 * (упоминается)", a "pairing" may hold one name or three — and the strings are the site's as written, only trimmed.
 * What the work's characters and pairings are is the models' to learn of it ([CastReader]).
 *
 * [characters]: every name the line names — alone and in its pairings — each once, in the order written. [pairings]:
 * every entry of more than one name, its names in the order written.
 */
class SaidCast(val characters: List<String>, val pairings: List<List<String>>) {
    companion object {
        /** Nothing said: a work whose site has no such line, or whose line is empty. */
        val NONE = SaidCast(emptyList(), emptyList())

        /**
         * The cast of the entries of a line already split apart ([entries]), each written as a pairing of names between
         * [separator]s or as a name alone.
         */
        fun of(entries: Collection<String>, separator: Char): SaidCast {
            val split = entries.map { e -> e.split(separator).map { it.trim() }.filter { it.isNotEmpty() } }
            return SaidCast(split.flatten().distinct(), split.filter { it.size > 1 })
        }
    }
}
