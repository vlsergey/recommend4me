package io.github.vlsergey.recommend4me.cast

import java.util.Locale

/**
 * THE FORM A NAME IS COMPARED IN, of a site's line and of the dictionary alike: in lower case, "ё" as "е", the spaces
 * between its words one. Nothing else is changed — no word dropped, no note in brackets cut off: "Гарри (упоминается)"
 * is a name of its own.
 */
internal object NameForm {
    private val SPACES = Regex("\\s+")

    fun of(name: String): String = name.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(SPACES, " ")

    /** A pairing in that form: its names so, in the order of their forms, apart by "|" — one whatever their order. */
    fun ofPairing(names: List<String>): String = names.map(::of).sorted().joinToString("|")
}
