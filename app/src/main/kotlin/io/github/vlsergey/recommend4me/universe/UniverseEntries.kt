package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.records.UniverseCharacterRecord
import io.github.vlsergey.recommend4me.database.dictionaries.tables.records.UniverseClassRecord
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_CHOICE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_PROPERTY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CLASS_CHOICE
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.LOCAL
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.SHOWN
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.decode
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.decodeAll
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * THE ENTRIES OF THE UNIVERSES OF THE DICTIONARY AND WHICH OF THEM ARE KEPT AS CHARACTERS, with the user's words on
 * them. AN ENTRY IS KEPT AS A CHARACTER by the user's word on it; else when any of its classes is kept — a class by the
 * user's word on it within the universe, else when its entries are characters; an entry the catalogue gave no class is
 * kept: nothing says it is not one.
 */
class UniverseEntries
internal constructor(
    private val db: DSLContext,
    /** The user's languages: the texts are shown in the first of them that has one. */
    private val languages: List<String>,
    /** The names the user gave the universes and the characters, read when no reading hands them over. */
    private val names: DictionaryNames,
) {

    /**
     * The entries of the universe kept as characters; the names the user gave first ([local], read once for a reading
     * of many universes).
     */
    fun characters(
        catalogue: String,
        id: String,
        local: Map<String, String> = names.localNames(),
    ): List<UniverseCharacter> = of(catalogue, id, local).filter { it.included }.map { it.character }

    /**
     * Every entry of the universe, kept or not, by name; the names the user gave first ([local], read once for a
     * reading of many universes).
     */
    fun of(catalogue: String, id: String, local: Map<String, String> = names.localNames()): List<KeptEntry> =
        UniverseKey(catalogue, id).let { key -> ofUniverses(listOf(key), local)[key].orEmpty() }

    /**
     * EVERY ENTRY OF EACH OF THE [universes] — of every universe of the dictionary when null — kept or not, by name,
     * the names the user gave first ([local]): every table read once for them all. A universe at a time was seven
     * queries a universe, and a reading of a catalogue's chances read every universe of the dictionary so — most of the
     * time of the facets' dictionaries.
     */
    fun ofUniverses(
        universes: Collection<UniverseKey>?,
        local: Map<String, String> = names.localNames(),
    ): Map<UniverseKey, List<KeptEntry>> {
        if (universes != null && universes.isEmpty()) return emptyMap()
        val classes = classesOf(universes)
        val ofEntry = entryClasses(universes)
        val choices = entryChoices(universes)
        val statements = entryStatements(universes)
        val out = HashMap<UniverseKey, MutableList<KeptEntry>>()
        db.selectFrom(UNIVERSE_CHARACTER)
            .where(within(universes, UNIVERSE_CHARACTER.CATALOGUE, UNIVERSE_CHARACTER.UNIVERSE_ID))
            .fetch()
            .forEach { r ->
                val key = UniverseKey(r.catalogue!!, r.universeId!!)
                val own = ofEntry[key]?.get(r.characterId!!).orEmpty()
                val stated = statements[key]?.get(r.characterId!!).orEmpty()
                val given = local[UniverseFacet.valueOf(key.catalogue, r.characterId!!)]
                val choice = choices[key]?.get(r.characterId!!)
                val kept = classes[key]
                out.getOrPut(key) { ArrayList() } +=
                    KeptEntry(
                        characterOf(r, own, stated, given),
                        choice ?: (own.isEmpty() || own.any { kept?.get(it)?.included == true }),
                        choice,
                        given,
                    )
            }
        return out.mapValues { (_, entries) -> entries.sortedBy { it.character.names.first().lowercase() } }
    }

    /**
     * The entry of the record [r] as a character: of the [classes] it is of, with the statements [stated] of it; the
     * name the user [given] it, as a label of a language of its own before the user's — its first name, and one more it
     * goes by.
     */
    private fun characterOf(
        r: UniverseCharacterRecord,
        classes: List<String>,
        stated: Map<String, List<String>>,
        given: String?,
    ): UniverseCharacter {
        val sex = r.sex?.let(CharacterSex::valueOf)
        val localLabel = listOfNotNull(given?.let { LOCAL to it }).toMap()
        val localFirst = if (given == null) emptyList() else listOf(LOCAL)
        return if (r.labels != null) {
            UniverseCharacter(
                r.characterId!!,
                localLabel + decode(r.labels),
                decodeAll(r.aliases),
                decode(r.descriptions),
                r.url!!,
                localFirst + languages,
                classes,
                sex,
                stated,
            )
        } else {
            // Saved before the texts were kept by language: its texts as they were shown then
            val names = r.names!!.split('\n')
            UniverseCharacter(
                r.characterId!!,
                localLabel + (SHOWN to names.first()),
                mapOf(SHOWN to names.drop(1)),
                listOfNotNull(r.description?.let { SHOWN to it }).toMap(),
                r.url!!,
                localFirst + languages + SHOWN,
                classes,
                sex,
                stated,
            )
        }
    }

    /** Of each of the [universes] — of every universe when null — the classes every entry is of. */
    private fun entryClasses(universes: Collection<UniverseKey>?): Map<UniverseKey, Map<String, List<String>>> {
        val cc = UNIVERSE_CHARACTER_CLASS
        val ofEntry = HashMap<UniverseKey, HashMap<String, MutableList<String>>>()
        db.select(cc.CATALOGUE, cc.UNIVERSE_ID, cc.CHARACTER_ID, cc.CLASS_ID)
            .from(cc)
            .where(within(universes, cc.CATALOGUE, cc.UNIVERSE_ID))
            .fetch {
                ofEntry
                    .getOrPut(UniverseKey(it.value1()!!, it.value2()!!)) { HashMap() }
                    .getOrPut(it.value3()!!) { ArrayList() } += it.value4()!!
            }
        return ofEntry
    }

    /** Of each of the [universes] — of every universe when null — the user's word on each entry it has one on. */
    private fun entryChoices(universes: Collection<UniverseKey>?): Map<UniverseKey, Map<String, Boolean>> {
        val ch = UNIVERSE_CHARACTER_CHOICE
        val choices = HashMap<UniverseKey, HashMap<String, Boolean>>()
        db.select(ch.CATALOGUE, ch.UNIVERSE_ID, ch.CHARACTER_ID, ch.INCLUDED)
            .from(ch)
            .where(within(universes, ch.CATALOGUE, ch.UNIVERSE_ID))
            .fetch {
                choices.getOrPut(UniverseKey(it.value1()!!, it.value2()!!)) { HashMap() }[it.value3()!!] = it.value4()!!
            }
        return choices
    }

    /**
     * Of each of the [universes] — of every universe when null — the statements of every entry: property to its values.
     */
    private fun entryStatements(
        universes: Collection<UniverseKey>?
    ): Map<UniverseKey, Map<String, Map<String, List<String>>>> {
        val p = UNIVERSE_CHARACTER_PROPERTY
        val statements = HashMap<UniverseKey, HashMap<String, MutableMap<String, MutableList<String>>>>()
        db.select(p.CATALOGUE, p.UNIVERSE_ID, p.CHARACTER_ID, p.PROPERTY, p.PROPERTY_VALUE)
            .from(p)
            .where(within(universes, p.CATALOGUE, p.UNIVERSE_ID))
            .fetch {
                statements
                    .getOrPut(UniverseKey(it.value1()!!, it.value2()!!)) { HashMap() }
                    .getOrPut(it.value3()!!) { HashMap() }
                    .getOrPut(it.value4()!!) { ArrayList() } += it.value5()!!
            }
        return statements
    }

    /** Every class the entries of the universe are of, the most entries first. */
    fun classes(catalogue: String, id: String): List<ClassInUniverse> =
        UniverseKey(catalogue, id).let { key ->
            classesOf(listOf(key))[key].orEmpty().values.sortedByDescending { it.entries }
        }

    /**
     * Of each of the [universes] — of every universe when null — the classes its entries are of: every table read once
     * for them all.
     */
    private fun classesOf(universes: Collection<UniverseKey>?): Map<UniverseKey, Map<String, ClassInUniverse>> {
        val cc = UNIVERSE_CHARACTER_CLASS
        val counts = HashMap<UniverseKey, HashMap<String, Int>>()
        db.select(cc.CATALOGUE, cc.UNIVERSE_ID, cc.CLASS_ID, DSL.count())
            .from(cc)
            .where(within(universes, cc.CATALOGUE, cc.UNIVERSE_ID))
            .groupBy(cc.CATALOGUE, cc.UNIVERSE_ID, cc.CLASS_ID)
            .fetch {
                counts.getOrPut(UniverseKey(it.value1()!!, it.value2()!!)) { HashMap() }[it.value3()!!] = it.value4()!!
            }
        if (counts.isEmpty()) return emptyMap()
        val wanted = HashMap<String, MutableSet<String>>()
        counts.forEach { (key, of) -> wanted.getOrPut(key.catalogue) { HashSet() } += of.keys }
        val known = HashMap<Pair<String, String>, UniverseClassRecord>()
        db.selectFrom(UNIVERSE_CLASS)
            .where(
                DSL.or(
                    wanted.map { (catalogue, ids) ->
                        UNIVERSE_CLASS.CATALOGUE.eq(catalogue).and(UNIVERSE_CLASS.CLASS_ID.`in`(ids))
                    }
                )
            )
            .fetch()
            .forEach { known[it.catalogue!! to it.classId!!] = it }
        val ch = UNIVERSE_CLASS_CHOICE
        val choices = HashMap<UniverseKey, HashMap<String, Boolean>>()
        db.select(ch.CATALOGUE, ch.UNIVERSE_ID, ch.CLASS_ID, ch.INCLUDED)
            .from(ch)
            .where(within(universes, ch.CATALOGUE, ch.UNIVERSE_ID))
            .fetch {
                choices.getOrPut(UniverseKey(it.value1()!!, it.value2()!!)) { HashMap() }[it.value3()!!] = it.value4()!!
            }
        return counts.mapValues { (key, of) ->
            of.mapValues { (cls, n) ->
                val k = known[key.catalogue to cls]
                val character = k?.isCharacter ?: false
                val choice = choices[key]?.get(cls)
                val name =
                    k?.let { if (it.labels != null) TextsByLanguage.shown(it.labels, languages) else it.name } ?: cls
                ClassInUniverse(cls, name, character, n, choice ?: character, choice)
            }
        }
    }

    /** The user's word on a class within the universe: its entries kept, or left out; null takes it back. */
    fun chooseClass(catalogue: String, id: String, cls: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CLASS_CHOICE)
            .where(
                UNIVERSE_CLASS_CHOICE.CATALOGUE.eq(catalogue),
                UNIVERSE_CLASS_CHOICE.UNIVERSE_ID.eq(id),
                UNIVERSE_CLASS_CHOICE.CLASS_ID.eq(cls),
            )
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CLASS_CHOICE)
                .set(UNIVERSE_CLASS_CHOICE.CATALOGUE, catalogue)
                .set(UNIVERSE_CLASS_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CLASS_CHOICE.CLASS_ID, cls)
                .set(UNIVERSE_CLASS_CHOICE.INCLUDED, included)
                .execute()
        }
    }

    /** The user's word on one entry of the universe, over its classes; null takes it back. */
    fun chooseEntry(catalogue: String, id: String, character: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CHARACTER_CHOICE)
            .where(
                UNIVERSE_CHARACTER_CHOICE.CATALOGUE.eq(catalogue),
                UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID.eq(id),
                UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID.eq(character),
            )
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CHARACTER_CHOICE)
                .set(UNIVERSE_CHARACTER_CHOICE.CATALOGUE, catalogue)
                .set(UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID, character)
                .set(UNIVERSE_CHARACTER_CHOICE.INCLUDED, included)
                .execute()
        }
    }
}

/** The rows of the [universes] alone, by a table's [catalogue] and [universe] fields; every row when null. */
private fun within(
    universes: Collection<UniverseKey>?,
    catalogue: Field<String?>,
    universe: Field<String?>,
): Condition =
    if (universes == null) DSL.noCondition()
    else
        DSL.or(
            universes.groupBy({ it.catalogue }, { it.id }).map { (c, ids) ->
                catalogue.eq(c).and(universe.`in`(ids.distinct()))
            }
        )
