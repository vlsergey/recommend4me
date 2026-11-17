package io.github.vlsergey.recommend4me.universe

/**
 * THE NAMES AND THE DESCRIPTIONS THE UNIVERSES ARE SHOWN BY: of a universe the name the user gave it, else the name of
 * the first entry it is bound to ([DictionaryNames.entryNames]), else the one worked out of its sites' fandoms; what
 * the catalogue says of it, of that entry.
 */
class FandomNames(
    private val names: DictionaryNames,
    private val fandoms: FandomRepository,
    private val read: () -> FandomDictionary,
) {

    /**
     * The name every universe of [values] is shown by — every universe's when null, of the [dictionary] read already or
     * read now; a value that is no universe, none. Of the [values] named, the names kept with them ([FandomShownNames])
     * read by their ids alone, no dictionary: of a page of the list a few dozen universes of hundreds of thousands
     * (user, 2026-11-17); of one changed since, worked out now of its own row and entries.
     */
    fun of(values: Collection<String>?, dictionary: FandomDictionary? = null): Map<String, String> {
        if (values == null) return computed(dictionary ?: read())
        val rows = fandoms.rowsOf(values.mapNotNull(FandomRepository::idOf))
        val kept = rows.values.filter { it.shownName != null }
        // Of a universe changed since its name was worked out — named, bound, told apart — its name worked out now
        val changed = rows.keys.filter { rows[it]?.shownName == null }
        val now =
            if (changed.isEmpty()) emptyMap() else named(Named(rows, changed, fandoms.entries.ofMany(changed)::get))
        return kept.associate { FandomRepository.value(it.id) to it.shownName!! } + now
    }

    /**
     * The name every universe of the [dictionary] is shown by, worked out now: its own, else its first entry's in the
     * user's languages, else the one worked out of its fandoms — what is kept ([FandomShownNames]).
     */
    fun computed(dictionary: FandomDictionary): Map<String, String> =
        named(Named(dictionary.fandoms, dictionary.fandoms.keys) { dictionary.entriesOf(it) })

    /** The universes [ids] of their [rows] and of what entries each is bound to ([entriesOf]). */
    private class Named(
        val rows: Map<Long, FandomRow>,
        val ids: Collection<Long>,
        val entriesOf: (Long) -> List<UniverseKey>?,
    )

    /**
     * The name of every universe of [read]: its own, else its first entry's, else the one worked out of its fandoms.
     */
    private fun named(read: Named): Map<String, String> {
        if (read.ids.isEmpty()) return emptyMap()
        val unnamed = read.ids.filter { read.rows[it]?.name == null }
        val entries = unnamed.associateWith { read.entriesOf(it)?.firstOrNull() }
        val entryNames = names.entryNames(entries.values.filterNotNull().map { it.value })
        return read.ids.associate { id ->
            val row = read.rows.getValue(id)
            FandomRepository.value(id) to
                (row.name ?: entries[id]?.let { entryNames[it.value] } ?: row.calculatedName ?: "#$id")
        }
    }

    /**
     * What the catalogue says of every universe of [values]: of the first entry it is bound to — of the [dictionary]
     * read already, else read by their ids.
     */
    fun descriptions(values: Collection<String>, dictionary: FandomDictionary? = null): Map<String, String> {
        val ids = values.mapNotNull { v -> FandomRepository.idOf(v)?.let { v to it } }.toMap()
        val entries =
            when {
                ids.isEmpty() -> emptyMap()
                dictionary != null -> ids.values.associateWith { dictionary.entriesOf(it) }
                else -> fandoms.entries.ofMany(ids.values)
            }
        val first = ids.mapNotNull { (v, id) -> entries[id]?.firstOrNull()?.let { v to it } }.toMap()
        val described = names.descriptions(first.values.map { it.value })
        return first.mapNotNull { (v, e) -> described[e.value]?.let { v to it } }.toMap()
    }
}
