package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.search.NamesAsked
import io.github.vlsergey.recommend4me.search.SearchProvider
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.CharacterSex
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.universe.UniverseKey
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The values of a facet of the universes besides the items' own, with the text their names are encoded from, and the
 * values each item may be given; null for any other facet.
 */
/**
 * The names a work's own words are looked for by: [names] — value to the names it goes by, one map for every work of
 * the same universes — but those of the values [without], the work's own few left out ([NamesAsked.without]). One for
 * every work of the same.
 */
internal class Named(val names: Map<String, List<String>>, val without: Set<String>) {
    /** The names looked for, those left out gone: of one work alone. */
    val whole: Map<String, List<String>>
        get() = if (without.isEmpty()) names else names - without
}

internal class Vocabulary(
    val values: Map<String, String>,
    val allowed: ((String) -> Set<String>)?,
    /**
     * The names a work's own words are looked for by in the index ([SearchProvider.mentions]) ([Named]): null for a
     * work not counted. [valuesOf]: the values a name found is of. Of the candidates of one work and of the passages
     * naming a value alone: no model reads them.
     */
    val names: ((String) -> Named?)? = null,
    val valuesOf: (String) -> Collection<String> = { listOf(it) },
    /**
     * Of the pairings: the [names] are of characters, a value two of them named in one paragraph
     * ([SearchProvider.together]).
     */
    val paired: Boolean = false,
    /** The groupings of the values the task is made of, in its order; none when they fall into no known groups. */
    val groupings: (List<String>) -> List<ValueGrouping> = { emptyList() },
    /**
     * The values the site gives each item as examples of the facet ([FacetDef.examplesFrom]); null for a facet without.
     */
    val examples: ((String) -> List<String>)? = null,
    /** How much a value an item lacks counts as one it has not ([SuggestionTask.lackWeight]). */
    val lackWeight: Double = 1.0,
    /**
     * Every name each value goes by, each a text that names it ([NameHint]); none for a facet whose values are not
     * named in texts.
     */
    val hintNames: Map<String, List<String>> = emptyMap(),
    /**
     * The values a text naming a value is judged among: the value and its rivals — the characters of its universes. One
     * collection for every value judged among the same: it is turned into the task's indices once ([NameHint.among]).
     */
    val hintAmong: (String) -> Collection<String> = { emptyList() },
    /**
     * Of each item, the values of its [allowed] it may not have after all ([SuggestionTask.barred]): [allowed] one set
     * for every work of the same universes, this the work's own; null: none.
     */
    val barred: ((String) -> Set<String>)? = null,
) {
    /**
     * How often each item's own words name each value, by [names], of the index of the [search] — a call for every map
     * of names shared by works; of the pairings, in how many paragraphs two characters are named together. Null when
     * the values are not counted.
     */
    fun mentions(
        store: SourceStore,
        ids: Collection<String>?,
        typeId: String,
        search: SearchProvider?,
    ): ((String) -> Map<String, Int>?)? {
        names?.let { of ->
            val provider = search ?: return null
            val wanted = ids ?: store.items.keys().map { it.id }
            val groups = IdentityHashMap<Named, MutableList<String>>()
            wanted.forEach { id -> of(id)?.let { groups.getOrPut(it) { ArrayList() } += id } }
            val counted = HashMap<String, Map<String, Int>>()
            // Every map of names with its works, asked of the index at once: one reading of it for them all.
            // Every work of the source when all of them are: the index read whole, not a work at a time
            val asked = groups.map { (named, works) ->
                NamesAsked(
                    if (ids == null && groups.size == 1) null else works.map { ItemKey(store.id, it) },
                    named.names,
                    named.without,
                )
            }
            if (paired)
                provider.together(typeId, asked).forEach { byItem ->
                    byItem.forEach { (key, pairs) ->
                        if (key.source == store.id)
                            counted[key.id] = pairs.mapKeys { (pair, _) ->
                                UniverseFacet.pairing(pair.first, pair.second)
                            }
                    }
                }
            else
                provider.mentions(typeId, asked).forEach { byItem ->
                    byItem.forEach { (key, byName) ->
                        if (key.source != store.id) return@forEach
                        val byValue = HashMap<String, Int>()
                        byName.forEach { (name, n) -> valuesOf(name).forEach { byValue.merge(it, n, Int::plus) } }
                        counted[key.id] = byValue
                    }
                }
            val within = ids?.toHashSet()
            // A work counted that names none has none, not an unknown count
            return { id -> if (within != null && id !in within || of(id) == null) null else counted[id].orEmpty() }
        }
        return null
    }
}

/**
 * THE DICTIONARIES OF THE FACETS: of a facet of the universes — the kinds, the universes, the main and the secondary
 * characters, the pairings — the values every work may have, their names and groups, the works' own word on them; of a
 * facet learnt from a site's facet (the tags), the site's examples. Of what the operation read: the dictionary's
 * characters ([CharactersRead]), the user's answers ([Answers]).
 */
internal class FacetVocabularies(private val stores: Stores) {
    /**
     * The vocabulary of the facet: of the universes, or of a facet learnt from the sites' facet; null of any other.
     * [light]: what scoring the works by the scoring kept needs alone — the values each may have, the site's examples —
     * no name of any value, no group: the scoring knows them. [site]: the site's values of the works ([ids], every work
     * when null) read already: not read again. [characters]: the dictionary's characters as the operation reads them,
     * every universe once; [answers] the user's answers on the works, as the operation read them.
     */
    fun vocabularyOf(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? =
        universeVocabulary(store, facet, ids, made, characters, answers, light, site)
            ?: examplesVocabulary(store, facet, ids, light, site)

    /**
     * The values of a facet of the layer learnt from a facet of the sites ([FacetDef.examplesFrom]): every value of
     * that facet of every source of the type, one by its name — "Магия" of one site and of another one tag — and every
     * item's own values of it as its examples; null for any other facet.
     *
     * A value the site lacks says little of the work: authors leave out most tags that fit. How little, the user's
     * answers tell — of the values the site did not give a work, the share the user rejected rather than confirmed, by
     * the rule of succession: as much as a lacking value weighs as a value the work has not.
     *
     * Of the works [ids] alone, when given, or [light] — read by the model kept, which knows its values — their
     * examples alone: the values of every source, their names and the user's answers are what a fitting learns from (of
     * every work of a source, read for nothing, five seconds). [site]: the site's values of those works read already.
     */
    private fun examplesVocabulary(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? {
        val from = store.schema.facets.firstOrNull { it.key == facet.examplesFrom } ?: return null
        if (ids != null || light) {
            val bound = store.tags.bindingsOf(store.id)
            val own = HashMap<String, List<String>>()
            fun put(id: String, keys: List<String>) {
                own[id] = keys.mapNotNull { bound[it]?.let(TagRepository::key) }.distinct()
            }
            if (site != null) (ids ?: site.keys).forEach { id -> put(id, site[id]?.get(from.key).orEmpty()) }
            else if (ids != null)
                store.items.facetsOf(ids).forEach { (id, facets) -> put(id, facets[from.key].orEmpty()) }
            else store.items.forEachFacet(from.key, ::put)
            return Vocabulary(emptyMap(), allowed = null, examples = { id -> own[id].orEmpty() })
        }
        // Every normalized tag a site's tag of the type is bound to, of every source of the type
        val bindings = store.tags.bindings()
        val values = HashMap<String, String>()
        stores.typeOf(store.id).sources.forEach { s ->
            val theirs =
                s.schema.facets.firstOrNull { it.standard != null && it.standard === from.standard } ?: return@forEach
            val names = s.names(theirs.key)
            s.items.valueUses(theirs.key).keys.forEach { key ->
                bindings[SiteTagRef(s.id, key)]?.let {
                    values.putIfAbsent(TagRepository.key(it), (names[key] ?: key).trim())
                }
            }
        }
        // A tag is read by its name as the user and the sites name it ([TagRepository])
        store.tags.shown(values.keys).forEach { (key, name) -> values[key] = name }
        val bound = store.tags.bindingsOf(store.id)
        val own = HashMap<String, List<String>>()
        store.items.forEachFacet(from.key) { id, keys ->
            own[id] = keys.mapNotNull { bound[it]?.let(TagRepository::key) }.distinct()
        }
        val answered =
            store.corrections.allFacets().filter { it.facet == facet.key && it.key !in own[it.itemId].orEmpty() }
        val denied = answered.count { !it.added }
        return Vocabulary(
            values,
            allowed = null,
            examples = { id -> own[id].orEmpty() },
            lackWeight = (denied + 1.0) / (answered.size + 2.0),
        )
    }

    /**
     * The vocabulary of a facet of the universes; of the works [ids], when given, what depends on the works read of
     * them alone — their universes, the characters of their other facet, their pairs — not of every work: of a work of
     * a large universe the chances of thousands of characters each. The values the model gave of the facets before it
     * are [made] in the chain; the dictionary's characters of [characters], every universe read once for the operation.
     */
    fun universeVocabulary(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? {
        val type = stores.typeOf(store.id)
        if (!type.type.universes) return null
        return when (facet.key) {
            // The kinds, the universes, the characters and the pairings are named by the dictionary, whatever the
            // source ([UniverseNames])
            UniverseFacets.KIND -> Vocabulary(UniverseFacet.KINDS, null)
            // Every universe of the dictionary, linked to a work or not yet — to a work not known to have none
            UniverseFacets.UNIVERSE -> {
                val kinds = HashMap<String, List<String>>()
                // The site's kinds of the works, of their values read already when they are
                if (site != null) site.forEach { (id, facets) -> facets[UniverseFacets.KIND]?.let { kinds[id] = it } }
                else if (ids == null) store.items.forEachFacet(UniverseFacets.KIND) { id, keys -> kinds[id] = keys }
                else
                    store.items.facetsOf(ids).forEach { (id, facets) ->
                        facets[UniverseFacets.KIND]?.let { kinds[id] = it }
                    }
                answers.all
                    .filter { it.facet == UniverseFacets.KIND }
                    .groupBy { it.itemId }
                    .forEach { (id, own) ->
                        kinds[id] =
                            Corrected.facets(mapOf(UniverseFacets.KIND to kinds[id].orEmpty()), own)[
                                    UniverseFacets.KIND]
                                .orEmpty()
                    }
                // Realism, an alternative history and an original work: our world, ours changed, or a world of its own
                fun original(id: String) =
                    kinds[id].orEmpty().let { it.isNotEmpty() && it.all { k -> k in UniverseFacets.UNIVERSELESS } }
                // NO NAME OF IT LOOKED FOR IN THE WORK'S WORDS (user, 2026-10-16): the universe is told by the
                // work's texts, its tags and its other values; the names below are what the fitting learns from.
                // Of the works read, the universes' keys alone: not every universe's characters read to count them
                if (light) {
                    val keys = type.universes.keys().map { it.value }.toSet()
                    return Vocabulary(emptyMap(), allowed = { id -> if (original(id)) emptySet() else keys })
                }
                val universes = type.universes.all(characters)
                val all = universes.associate { it.value to it.name }
                // A universe is named by its characters ("Гарри", "Хогвартс") and by its own name
                val ofCharacter = HashMap<String, MutableSet<String>>()
                val names = HashMap<String, List<String>>()
                universes.forEach { u ->
                    names[u.value] = u.names
                    characters.of(UniverseKey(u.catalogue, u.id)).forEach { c ->
                        val value = UniverseFacet.valueOf(u.catalogue, c.id)
                        names[value] = c.names
                        ofCharacter.getOrPut(value) { HashSet() } += u.value
                    }
                }
                // Every name of a universe and of each of its characters is a text that names it, and no other universe
                val hintNames = HashMap<String, MutableList<String>>()
                names.forEach { (value, own) ->
                    (ofCharacter[value] ?: setOf(value).filter { it in all }).forEach { u ->
                        hintNames.getOrPut(u) { ArrayList() } += own
                    }
                }
                Vocabulary(
                    all,
                    allowed = { id -> if (original(id)) emptySet() else all.keys },
                    hintNames = hintNames.mapValues { it.value.distinct() },
                    hintAmong = { all.keys },
                )
            }
            // The main characters and the secondary ones alike, each without what the other has
            UniverseFacets.CHARACTERS,
            UniverseFacets.SECONDARY_CHARACTERS -> {
                // Every character of every universe — by all its names: the fans write the one they like —
                // and the original characters; a work is given those of its own universes. Of the works
                // read, the characters of their universes alone: no other is chosen from ([FacetData.load])
                val universes = workUniverses(answers, made)
                // The universes by their keys alone: their characters read below, of those wanted
                val read =
                    type.universes.keys().let { all ->
                        if (ids == null) all
                        else
                            ids.flatMap { universes[it].orEmpty() }
                                .toSet()
                                .let { own -> all.filter { it.value in own } }
                    }
                val ofUniverse = HashMap<String, Set<String>>()
                val values = HashMap<String, String>(UniverseFacet.ORIGINAL_CHARACTERS)
                val names = HashMap<String, List<String>>()
                // Their sexes of the same reading: a universe's characters read once
                val sexes = HashMap<String, CharacterSex>(UniverseFacet.ORIGINAL_SEXES)
                // Their entries read at once: of every work every universe of the dictionary
                if (ids == null) characters.every() else characters.read(read)
                read.forEach { u ->
                    val own = characters.of(u)
                    ofUniverse[u.value] = own.map { UniverseFacet.valueOf(u.catalogue, it.id) }.toSet()
                    own.forEach { c ->
                        val value = UniverseFacet.valueOf(u.catalogue, c.id)
                        values[value] = c.names.joinToString(" / ")
                        names[value] = c.names
                        c.sex?.let { sexes[value] = it }
                    }
                }
                val out = leftOut(answers, facet.key, made)
                // The characters of a work's universes, looked for in its words: a work of none is not counted.
                // One map of names for every work of the same universes, made once for them all, the work's
                // characters left out told apart — not a map of a universe's thousands of characters made and
                // compared a work — the index reading the names once for every work of the universes
                val ofUniverses = HashMap<Set<String>, Map<String, List<String>>>()
                val nameSets = HashMap<Pair<Set<String>, Set<String>>, Named>()
                val named = { id: String ->
                    val us = universes[id].orEmpty().toSet()
                    val own =
                        ofUniverses.getOrPut(us) {
                            us.flatMap { ofUniverse[it].orEmpty() }.toSet().associateWith { names[it].orEmpty() }
                        }
                    val left = out(id).filterTo(HashSet()) { it in own }
                    if (left.size == own.size) null else nameSets.getOrPut(us to left) { Named(own, left) }
                }
                // What a work may have: one set for every work of the same universes — of a large universe
                // thousands of characters, not a copy a work — its own characters of the other facet barred
                val unions = ConcurrentHashMap<Set<String>, Set<String>>()
                fun mayOf(id: String) =
                    universes[id].orEmpty().toSet().let { us ->
                        unions.computeIfAbsent(us) {
                            us.flatMap { ofUniverse[it].orEmpty() }.toSet() + UniverseFacet.ORIGINAL_CHARACTERS.keys
                        }
                    }
                val barred = { id: String ->
                    out(id).filterTo(HashSet()) { it in mayOf(id) && it !in UniverseFacet.ORIGINAL_CHARACTERS }
                }
                if (light) return Vocabulary(emptyMap(), allowed = ::mayOf, names = named, barred = barred)
                // A name of a character names it and none of the other characters of its universes:
                // judged among them all, one set for every character of the same universes
                val universesOf = HashMap<String, MutableSet<String>>()
                ofUniverse.forEach { (u, members) -> members.forEach { universesOf.getOrPut(it) { HashSet() } += u } }
                val among = HashMap<Set<String>, Set<String>>()
                universesOf.values.forEach { us ->
                    among.getOrPut(us) { us.flatMap { ofUniverse[it].orEmpty() }.toSet() }
                }
                Vocabulary(
                    values,
                    hintNames = names,
                    hintAmong = { c -> universesOf[c]?.let(among::getValue).orEmpty() },
                    allowed = ::mayOf,
                    barred = barred,
                    names = named,
                    // A work's tags tell the sex of its main characters
                    groupings = { list ->
                        listOf(
                            ValueGrouping(
                                "sex",
                                CharacterSex.entries.map { it.name },
                                list.map { c -> sexes[c]?.let { intArrayOf(it.ordinal) } ?: IntArray(0) },
                            )
                        )
                    },
                )
            }
            UniverseFacets.PAIRINGS -> {
                // Every pair of a work's characters: the confirmed ones and those the model gives it
                val linkedCharacters = linked(answers, UniverseFacets.CHARACTERS).toMutableMap()
                made.forEach { (id, chances) ->
                    val given = ModelValues.given(chances[UniverseFacets.CHARACTERS].orEmpty())
                    if (given.isNotEmpty()) linkedCharacters[id] = linkedCharacters[id].orEmpty() + given
                }
                val rejected =
                    answers.all
                        .filter { it.facet == UniverseFacets.CHARACTERS && !it.added }
                        .groupBy({ it.itemId }, { it.key })
                // The original characters pair with anyone: a work need not list them among its main ones
                val pairs = linkedCharacters.mapValues { (id, own) ->
                    val kept =
                        (own + UniverseFacet.ORIGINAL_CHARACTERS.keys - rejected[id].orEmpty().toSet())
                            .distinct()
                            .sorted()
                    kept.flatMapIndexed { k, a -> kept.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
                }
                // The names of the characters of the works' universes, looked for in their words: two named in one
                // paragraph. The original characters have no names to count; one map for every work of the same
                // characters
                val named = pairedNames(store, answers, made, characters, linkedCharacters)
                if (light)
                    return Vocabulary(emptyMap(), allowed = { id -> pairs[id].orEmpty() }, names = named, paired = true)
                val names =
                    UniverseFacet.ORIGINAL_CHARACTERS +
                        store.names(
                            UniverseFacets.CHARACTERS,
                            pairs.values.flatten().flatMap { p -> UniverseFacet.members(p)?.toList().orEmpty() },
                        )
                val values =
                    pairs.values.flatten().toSet().associateWith { p ->
                        UniverseFacet.members(p)!!.let { (a, b) -> "${names[a] ?: a} / ${names[b] ?: b}" }
                    }
                // Their names alone are what the model reads; shown with the sign of their characters' sexes
                // ([UniverseNames])
                val sexes = UniverseFacet.sexes(type.universes, characters)
                // The sexes of a pairing's two characters, and whether they are one sex: two groupings
                val kinds =
                    CharacterSex.entries.flatMap { x -> CharacterSex.entries.filter { it >= x }.map { x to it } }
                // A pairing of two characters of the dictionary is named "A / B"; it names no other pairing of either
                // of them
                val ofMember = HashMap<String, MutableSet<String>>()
                values.keys.forEach { p ->
                    UniverseFacet.members(p)?.toList()?.forEach { ofMember.getOrPut(it) { HashSet() } += p }
                }
                val ofTwoKnown = values.filterKeys { p ->
                    UniverseFacet.members(p)?.toList()?.none { it in UniverseFacet.ORIGINAL_CHARACTERS } == true
                }
                Vocabulary(
                    values,
                    hintNames = ofTwoKnown.mapValues { listOf(it.value) },
                    // Its own among them: a pairing is one of each of its characters'
                    hintAmong = { p ->
                        UniverseFacet.members(p)?.toList().orEmpty().flatMap { ofMember[it].orEmpty() }.toSet()
                    },
                    allowed = { id -> pairs[id].orEmpty() },
                    names = named,
                    paired = true,
                    groupings = { list ->
                        val of = list.map { UniverseFacet.sexesOf(it, sexes) }
                        fun one(group: Int?) = group?.let { intArrayOf(it) } ?: IntArray(0)
                        // A pairing is in the groups of its two characters: what is learnt of a
                        // character's pairings holds for a pairing of it never seen with a work
                        val members = list.map { UniverseFacet.members(it)?.toList().orEmpty() }
                        val characters = members.flatten().distinct().sorted()
                        val at = characters.withIndex().associate { (i, c) -> c to i }
                        listOf(
                            ValueGrouping(
                                "sexes",
                                kinds.map { (x, y) -> "$x+$y" },
                                of.map { one(it?.let(kinds::indexOf)) },
                            ),
                            ValueGrouping(
                                "one sex",
                                listOf("same", "different"),
                                of.map { s ->
                                    one(
                                        s?.takeIf { (x, y) -> x != CharacterSex.OTHER && y != CharacterSex.OTHER }
                                            ?.let { (x, y) -> if (x == y) 0 else 1 }
                                    )
                                },
                            ),
                            ValueGrouping(
                                "characters",
                                characters,
                                members.map { m -> m.map { at.getValue(it) }.toIntArray() },
                            ),
                        )
                    },
                )
            }
            else -> null
        }
    }

    /**
     * The names of every work's characters [characters] — of the universes of the works [ids], every work when null — a
     * map for every work of two characters and more besides the original ones, one map for every work of the same: what
     * two of them named in one paragraph are looked for by.
     */
    private fun pairedNames(
        store: SourceStore,
        answers: Answers,
        made: WorkChances,
        read: CharactersRead,
        characters: Map<String, List<String>>,
    ): (String) -> Named? {
        val type = stores.typeOf(store.id)
        val universes = workUniverses(answers, made)
        val own = universes.values.flatten().toSet()
        val known = HashMap<String, List<String>>()
        val wanted = type.universes.keys().filter { it.value in own }
        read.read(wanted)
        wanted.forEach { u ->
            read.of(u).forEach { known[UniverseFacet.valueOf(u.catalogue, it.id)] = it.names }
        }
        val missing =
            characters.values.flatten().toSet().filter { it !in known && it !in UniverseFacet.ORIGINAL_CHARACTERS }
        val shown = store.names(UniverseFacets.CHARACTERS, missing)
        val sets = HashMap<Set<String>, Named>()
        return { id ->
            val named = characters[id].orEmpty().toSet() - UniverseFacet.ORIGINAL_CHARACTERS.keys
            if (named.size < 2) null
            else
                sets.getOrPut(named) {
                    Named(named.associateWith { known[it] ?: listOfNotNull(shown[it]) }, emptySet())
                }
        }
    }

    /**
     * The characters left out of the [facet] of every item ([UniverseFacet.leftOut]): those of its other facet of
     * characters — the user's answers, and the main ones the model gives it. The main characters are worked out before
     * the secondary ones in the chain: [made] has them.
     */
    fun leftOut(answers: Answers, facet: String, made: WorkChances): (String) -> Set<String> {
        if (UniverseFacet.otherCharacters(facet) == null) return { emptySet() }
        val secondary = facet == UniverseFacets.SECONDARY_CHARACTERS
        return { id ->
            val own = answers.byItem[id].orEmpty().filter { it.facet in UniverseFacet.CHARACTER_FACETS }
            // The main characters the model gives the work: of this work alone, as it is asked of
            val given =
                if (secondary) ModelValues.given(made[id]?.get(UniverseFacets.CHARACTERS).orEmpty()) else emptyList()
            fun said(f: String) = own.filter { it.facet == f && it.added }.map { it.key }
            fun has(f: String) =
                ((if (f == UniverseFacets.CHARACTERS) given else emptyList()) + said(f)).toSet() -
                    own.filter { it.facet == f && !it.added }.map { it.key }.toSet()
            UniverseFacet.leftOut(facet, ::has, ::said)
        }
    }

    /**
     * The universes whose characters every work may have: the ones the user confirmed, and the ones the model gives it
     * — more likely than not — unless the user rejected them; none but the confirmed once the user said the work has no
     * other universes. The universes the model gives are [made] before the characters in the chain.
     */
    private fun workUniverses(answered: Answers, made: WorkChances): Map<String, List<String>> {
        val answers = answered.all.filter { it.facet == UniverseFacets.UNIVERSE }.groupBy { it.itemId }
        val complete = answered.complete
        val given = made
        return (answers.keys + given.keys).associateWith { id ->
            val own = answers[id].orEmpty()
            val confirmed = own.filter { it.added }.map { it.key }
            if (UniverseFacets.UNIVERSE in complete[id].orEmpty()) confirmed
            else
                (confirmed + ModelValues.given(given[id]?.get(UniverseFacets.UNIVERSE).orEmpty()) -
                        own.filter { !it.added }.map { it.key }.toSet())
                    .distinct()
        }
    }

    /** The values of a facet the user linked every item — the items [ids], when given — to: item to values. */
    private fun linked(answers: Answers, facet: String): Map<String, List<String>> =
        answers.all.filter { it.facet == facet && it.added }.groupBy({ it.itemId }, { it.key })
}
