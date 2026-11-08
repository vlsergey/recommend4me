package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.util.IdentityHashMap

/**
 * THE NAMES FOUND IN THE DICTIONARY: a name of the line that is, in its form ([NameForm]), one of the names a character
 * of the work's universes goes by names that character — every character of the name when several share it, none chosen
 * among them; a pairing of the line whose two names name two characters names their pairing, every two of a pairing of
 * three. The values named are the site's word on them the model weighs ([CastRead.named]) — the table of a dictionary's
 * names, as the linking of a name to its entry begins (BLINK, ReFinED): exact, a name written otherwise found by none.
 */
class DictionaryNames : CastReader {
    override val id = ID

    override fun read(works: CastWorks): CastRead =
        CastRead(emptyList(), List(works.count) { emptyList() }, named(works))

    companion object {
        const val ID = "cast-dictionary"

        /** Of every work, the values of the task its line names. */
        internal fun named(works: CastWorks): List<List<String>> {
            // The characters by the forms of their names, made once of every map of the read: one map for every
            // work of the same universes
            val indexes = IdentityHashMap<Map<String, List<String>>, Map<String, List<String>>>()
            return works.casts.mapIndexed { i, cast ->
                val characters = works.characters[i] ?: return@mapIndexed emptyList()
                val byName = indexes.getOrPut(characters) { byForm(characters) }
                if (works.paired) pairings(cast, byName)
                else cast.characters.flatMap { byName[NameForm.of(it)].orEmpty() }.distinct()
            }
        }

        /** Of every pairing of the [cast], every two of its names: the pairings of the characters they name. */
        private fun pairings(cast: SaidCast, byName: Map<String, List<String>>): List<String> =
            cast.pairings
                .flatMap { names ->
                    val of = names.map { byName[NameForm.of(it)].orEmpty() }
                    of.indices.flatMap { a ->
                        (a + 1 until of.size).flatMap { b ->
                            of[a].flatMap { x -> of[b].filter { it != x }.map { y -> UniverseFacets.pairing(x, y) } }
                        }
                    }
                }
                .distinct()

        /** The [characters] by the form of every name each goes by. */
        private fun byForm(characters: Map<String, List<String>>): Map<String, List<String>> {
            val out = HashMap<String, MutableList<String>>()
            characters.forEach { (character, names) ->
                names.forEach { name ->
                    val list = out.getOrPut(NameForm.of(name)) { ArrayList() }
                    if (character !in list) list += character
                }
            }
            return out
        }
    }
}
