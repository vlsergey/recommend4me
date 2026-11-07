package io.github.vlsergey.recommend4me.universe

/**
 * THE TEXTS OF AN ENTRY OF THE DICTIONARY BY LANGUAGE, as the dictionary keeps them: a line each,
 * "<language><TAB><text>" — its labels, its aliases, its descriptions.
 */
internal object TextsByLanguage {
    /**
     * The language of the texts of an entry saved before they were kept by language: the ones shown then, the last
     * choice.
     */
    const val SHOWN = ""

    /** The "language" of the name the user gave an entry: before every language of the user's. */
    const val LOCAL = "local"

    /** Texts by language as kept: a line each, "<language><TAB><text>". */
    fun encode(texts: Map<String, String>): String = encodeAll(texts.mapValues { listOf(it.value) })

    /** Texts by language, several of a language, as kept: a line each. */
    fun encodeAll(texts: Map<String, List<String>>): String =
        texts.flatMap { (lang, list) -> list.map { "$lang\t${it.replace('\n', ' ')}" } }.joinToString("\n")

    /** The texts kept ([encodeAll]) by language, every one of a language. */
    fun decodeAll(kept: String?): Map<String, List<String>> =
        kept.orEmpty().lines().filter { '\t' in it }.groupBy({ it.substringBefore('\t') }, { it.substringAfter('\t') })

    /** The texts kept ([encode]) by language, the first of a language. */
    fun decode(kept: String?): Map<String, String> = decodeAll(kept).mapValues { it.value.first() }

    /**
     * The text of [byLanguage] (as kept: [encode]) in the first of the [languages] that has one; null when none does,
     * or none was kept.
     */
    fun shown(byLanguage: String?, languages: List<String>): String? =
        decode(byLanguage).let { texts -> languages.firstNotNullOfOrNull { texts[it] } }
}
