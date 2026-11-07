package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.preferences.CatalogueEntity
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.decode
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.decodeAll
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE NAMES AND THE DESCRIPTIONS THE DICTIONARY SHOWS ITS UNIVERSES AND CHARACTERS BY: the name the user gave one, else
 * the catalogue's in the first of the user's languages that has it — the names the user gives in the user's
 * preferences, the catalogue's in the dictionaries.
 */
class DictionaryNames
internal constructor(
    private val db: DSLContext,
    /** The user's languages: the texts are shown in the first of them that has one. */
    private val languages: List<String>,
    /** The names the user gives the universes and the characters, in the user's preferences. */
    private val renames: CatalogueRenameRepository,
) {

    /**
     * The names the user gave the universes and the characters over the catalogue's: value ("<catalogue>:<id>") to
     * name.
     */
    fun localNames(): Map<String, String> =
        renames.all().mapKeys { (e, _) -> UniverseFacet.valueOf(e.catalogue, e.entity) }

    /**
     * The name the user gives a universe or a character of [catalogue] over the catalogue's; null or blank takes it
     * back.
     */
    fun setLocalName(catalogue: String, id: String, name: String?, now: Instant) =
        renames.rename(CatalogueEntity(catalogue, id), name, now)

    /**
     * The name of every universe of [values] ("<catalogue>:<id>") in the dictionary, in the user's languages; of every
     * universe when null.
     */
    fun universeNames(values: Collection<String>?): Map<String, String> {
        val u = UNIVERSE
        val query = db.select(u.CATALOGUE, u.UNIVERSE_ID, u.LABELS, u.NAME).from(u)
        val rows =
            if (values == null) query.fetch()
            else {
                val wanted =
                    byCatalogue(values).ifEmpty {
                        return emptyMap()
                    }
                query
                    .where(
                        DSL.or(
                            wanted.map { (catalogue, ids) -> u.CATALOGUE.eq(catalogue).and(u.UNIVERSE_ID.`in`(ids)) }
                        )
                    )
                    .fetch()
            }
        val local = localNames()
        return rows.associate { r ->
            val id = r.value2()!!
            val value = UniverseFacet.valueOf(r.value1()!!, id)
            value to
                (local[value]
                    ?: if (r.value3() != null) TextsByLanguage.shown(r.value3(), languages) ?: id else r.value4()!!)
        }
    }

    /**
     * The name every character of [values] ("<catalogue>:<id>") is shown by — the first of its names, as
     * [UniverseCharacter.names] orders them — and its sex, of whichever universe of the dictionary has it; of every
     * character when null.
     */
    fun characterNames(values: Collection<String>?): Map<String, Pair<String, CharacterSex?>> {
        val c = UNIVERSE_CHARACTER
        val query = db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.ALIASES, c.NAMES, c.SEX).from(c)
        val rows =
            if (values == null) query.fetch()
            else {
                val wanted =
                    byCatalogue(values).ifEmpty {
                        return emptyMap()
                    }
                query
                    .where(
                        DSL.or(
                            wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) }
                        )
                    )
                    .fetch()
            }
        val local = localNames()
        return rows.associate { r ->
            val id = r.value2()!!
            val name =
                local[UniverseFacet.valueOf(r.value1()!!, id)]
                    ?: if (r.value3() != null) {
                        val labels = decode(r.value3())
                        val aliases = decodeAll(r.value4())
                        languages.firstNotNullOfOrNull { labels[it] }
                            ?: languages.firstNotNullOfOrNull { aliases[it]?.firstOrNull() }
                            ?: id
                    } else {
                        // Saved before the texts were kept by language: as it was shown then
                        r.value5()!!.substringBefore('\n')
                    }
            UniverseFacet.valueOf(r.value1()!!, id) to (name to r.value6()?.let(CharacterSex::valueOf))
        }
    }

    /**
     * Every name each character of [values] ("<catalogue>:<id>") goes by in the user's languages — the name the user
     * gave it, then the labels and aliases language by language — for a search by any of them; one read of the table.
     */
    fun characterAllNames(values: Collection<String>): Map<String, List<String>> {
        val wanted =
            byCatalogue(values).ifEmpty {
                return emptyMap()
            }
        val c = UNIVERSE_CHARACTER
        val local = localNames()
        val out = HashMap<String, List<String>>()
        db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.ALIASES, c.NAMES)
            .from(c)
            .where(DSL.or(wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) }))
            .fetch()
            .forEach { r ->
                val value = UniverseFacet.valueOf(r.value1()!!, r.value2()!!)
                val own =
                    if (r.value3() != null) {
                        val labels = decode(r.value3())
                        val aliases = decodeAll(r.value4())
                        languages.flatMap { listOfNotNull(labels[it]) + aliases[it].orEmpty() }
                    } else {
                        // Saved before the texts were kept by language: as it was shown then
                        r.value5()!!.split('\n')
                    }
                // A character of several universes is one entry: the same names in each
                out.putIfAbsent(value, (listOfNotNull(local[value]) + own).distinct())
            }
        return out
    }

    /**
     * What the catalogue says of [values] — universes and characters of the dictionary — in the first of the user's
     * languages that has it: value to description; one read of each table.
     */
    fun descriptions(values: Collection<String>): Map<String, String> {
        val wanted =
            byCatalogue(values).ifEmpty {
                return emptyMap()
            }
        val u = UNIVERSE
        val c = UNIVERSE_CHARACTER
        val out = HashMap<String, String>()
        db.select(u.CATALOGUE, u.UNIVERSE_ID, u.LABELS, u.DESCRIPTIONS, u.DESCRIPTION)
            .from(u)
            .where(DSL.or(wanted.map { (catalogue, ids) -> u.CATALOGUE.eq(catalogue).and(u.UNIVERSE_ID.`in`(ids)) }))
            .fetch()
            .forEach { r ->
                // Of a universe saved before the texts were kept by language, the one shown then
                val text = if (r.value3() != null) TextsByLanguage.shown(r.value4(), languages) else r.value5()
                text?.takeIf { it.isNotBlank() }?.let { out[UniverseFacet.valueOf(r.value1()!!, r.value2()!!)] = it }
            }
        // A character of several universes is one entry: its description the same in each
        db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.DESCRIPTIONS, c.DESCRIPTION)
            .from(c)
            .where(DSL.or(wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) }))
            .fetch()
            .forEach { r ->
                val text = if (r.value3() != null) TextsByLanguage.shown(r.value4(), languages) else r.value5()
                text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.putIfAbsent(UniverseFacet.valueOf(r.value1()!!, r.value2()!!), it) }
            }
        return out
    }

    /** The ids of [values] ("<catalogue>:<id>") by catalogue; a value of no catalogue left out. */
    private fun byCatalogue(values: Collection<String>): Map<String, List<String>> =
        values.mapNotNull(UniverseFacet::parse).groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
}
