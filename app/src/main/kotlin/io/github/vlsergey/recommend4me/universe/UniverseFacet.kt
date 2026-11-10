package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * The facets the application gives every work of a content type with universes ([UniverseFacets]). Their values are
 * kept as the user's corrections of the work's source — the site has none — and worked out by the model, as any
 * facet's:
 *
 * - the kind — fan fiction, realism, an alternative history, an original work — is the site's when it says, suggested
 *   otherwise;
 * - the universes are worked out of every universe of the dictionary for a work that is not known to have none, and
 *   given to it: the work's characters are chosen within them;
 * - the main characters are worked out of the characters of the work's universes and the original characters, the
 *   secondary ones the same way, the pairings of pairs of the work's main characters — given to the work at once. A
 *   character is a work's main one or its secondary one, never both ([leftOut]).
 *
 * All five are of the application's layer over the source: the user corrects them. Their definitions in a source's
 * schema: [UniverseFacetDefs].
 */
object UniverseFacet {
    const val KEY = UniverseFacets.UNIVERSE

    /** The values of the kind of a work, with their names as the model and the search read them. */
    val KINDS = UniverseFacets.KIND_FACET.values!!.associate { it.key to it.modelName }

    /**
     * The source's facet of the site's fandoms, the works' universes told by them ([FandomSites]); null when it has
     * none.
     */
    fun fandomFacet(schema: SourceSchema): String? =
        schema.facets.firstOrNull { it.standard === UniverseFacets.FANDOM_FACET }?.key

    /** The value of a universe or a character of a catalogue: "wikidata:Q8337". */
    fun valueOf(catalogue: String, id: String) = "$catalogue:$id"

    /** The catalogue and the id of a value; null when it is not one. */
    fun parse(value: String): Pair<String, String>? =
        value.indexOf(':').takeIf { it > 0 }?.let { value.substring(0, it) to value.substring(it + 1) }

    /**
     * The original characters of a work, of no universe: the values every work may have besides its universes'
     * characters, with their names as the model and the search read them — one language, whatever the user reads; the
     * user is shown them by their keys ([originalCharacterKey]).
     */
    val ORIGINAL_CHARACTERS =
        mapOf(
            "oc:female" to "ОЖП (оригинальный женский персонаж)",
            "oc:male" to "ОМП (оригинальный мужской персонаж)",
        )

    /**
     * The key of the name of an original character ("oc:female" — "standard:character.oc-female"); null of any other
     * value.
     */
    fun originalCharacterKey(value: String): String? =
        value
            .takeIf { it in ORIGINAL_CHARACTERS }
            ?.let { I18nKeys.qualified(I18nKeys.STANDARD, "character." + it.replace(':', '-')) }

    /** The two facets of a work's characters: a character is in one of them, never in both. */
    val CHARACTER_FACETS = listOf(UniverseFacets.CHARACTERS, UniverseFacets.SECONDARY_CHARACTERS)

    /** The other facet of a work's characters; null for any other facet. */
    fun otherCharacters(facet: String): String? =
        when (facet) {
            UniverseFacets.CHARACTERS -> UniverseFacets.SECONDARY_CHARACTERS
            UniverseFacets.SECONDARY_CHARACTERS -> UniverseFacets.CHARACTERS
            else -> null
        }

    /**
     * The characters left out of the [facet] of a work — not given, not offered, not shown even crossed out — for they
     * are in its other facet of characters:
     * - of the secondary ones, every main one the work has: the user's, the model's;
     * - of the main ones, every one the user said is secondary and not main. The model's word alone yields to the
     *   user's; of the model's word alone the main characters, worked out first, come first. The original characters
     *   are many persons: never left out.
     *
     * [has] gives what the work has of a facet — the site's and the model's with the user's answers on top; [said] what
     * the user said it has.
     */
    fun leftOut(facet: String, has: (String) -> Collection<String>, said: (String) -> Collection<String>): Set<String> =
        when (facet) {
            UniverseFacets.SECONDARY_CHARACTERS -> has(UniverseFacets.CHARACTERS).toSet()
            UniverseFacets.CHARACTERS ->
                said(UniverseFacets.SECONDARY_CHARACTERS).toSet() - said(UniverseFacets.CHARACTERS).toSet()
            else -> emptySet()
        } - ORIGINAL_CHARACTERS.keys

    /** The sexes of the original characters. */
    val ORIGINAL_SEXES = mapOf("oc:female" to CharacterSex.FEMALE, "oc:male" to CharacterSex.MALE)

    /** The sex of every character of the dictionary of a type, and of the original characters: value to sex. */
    fun sexes(
        universes: UniverseRepository,
        read: CharactersRead = CharactersRead(universes),
    ): Map<String, CharacterSex> {
        val out = HashMap<String, CharacterSex>(ORIGINAL_SEXES)
        read.every()
        universes.entryKeys().forEach { u ->
            read.of(u).forEach { c -> c.sex?.let { out[valueOf(u.catalogue, c.id)] = it } }
        }
        return out
    }

    /**
     * The page in its catalogue of every universe of the dictionary of a type — of the first entry it is bound to — and
     * of every character of the entries: value to address.
     */
    fun pages(universes: UniverseRepository): Map<String, String> {
        val out = HashMap<String, String>()
        val read = CharactersRead(universes)
        val entries = universes.allEntries(read).associateBy { UniverseKey(it.catalogue, it.id) }
        entries.forEach { (key, _) -> read.of(key).forEach { c -> out[valueOf(key.catalogue, c.id)] = c.url } }
        val dictionary = universes.dictionary()
        dictionary.fandoms.keys.forEach { id ->
            dictionary
                .entriesOf(id)
                .firstNotNullOfOrNull { entries[it] }
                ?.let { out[FandomRepository.value(id)] = it.url }
        }
        return out
    }

    /**
     * The sexes of a pairing's two characters, in the order of [CharacterSex]; null when the value is no pairing or a
     * sex is not known.
     */
    fun sexesOf(pairing: String, sexes: Map<String, CharacterSex>): Pair<CharacterSex, CharacterSex>? {
        val (x, y) = members(pairing)?.let { (a, b) -> sexes[a] to sexes[b] } ?: return null
        return when {
            x == null || y == null -> null
            x <= y -> x to y
            else -> y to x
        }
    }

    /** A pairing of two characters: one whatever their order. */
    fun pairing(a: String, b: String): String = UniverseFacets.pairing(a, b)

    /** The two characters of a pairing; null when the value is not one. */
    fun members(pairing: String): Pair<String, String>? =
        pairing
            .removePrefix("pair:")
            .takeIf { it != pairing }
            ?.split('|')
            ?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
}
