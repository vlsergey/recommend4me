package io.github.vlsergey.recommend4me.universe

/**
 * THE NAMES AND THE DESCRIPTIONS THE UNIVERSES ARE SHOWN BY: of a universe the name the user gave it, else the name of
 * the first entry it is bound to ([DictionaryNames.entryNames]), else the one worked out of its sites' fandoms; what
 * the catalogue says of it, of that entry.
 */
class FandomNames(private val names: DictionaryNames, private val read: () -> FandomDictionary) {

    /**
     * The name every universe of [values] is shown by — every universe's when null; a value that is no universe, none.
     */
    fun of(values: Collection<String>?, dictionary: FandomDictionary = read()): Map<String, String> {
        val ids =
            values?.mapNotNull(FandomRepository::idOf)?.filter { it in dictionary.fandoms } ?: dictionary.fandoms.keys
        if (ids.isEmpty()) return emptyMap()
        val unnamed = ids.filter { dictionary.fandoms[it]?.name == null }
        val entries = unnamed.associateWith { dictionary.entriesOf(it).firstOrNull() }
        val entryNames = names.entryNames(entries.values.filterNotNull().map { it.value })
        return ids.associate { id ->
            val row = dictionary.fandoms.getValue(id)
            FandomRepository.value(id) to
                (row.name ?: entries[id]?.let { entryNames[it.value] } ?: row.calculatedName ?: "#$id")
        }
    }

    /** What the catalogue says of every universe of [values]: of the first entry it is bound to. */
    fun descriptions(values: Collection<String>, dictionary: FandomDictionary = read()): Map<String, String> {
        val first =
            values
                .mapNotNull { v ->
                    FandomRepository.idOf(v)?.let { dictionary.entriesOf(it).firstOrNull() }?.let { v to it }
                }
                .toMap()
        val described = names.descriptions(first.values.map { it.value })
        return first.mapNotNull { (v, e) -> described[e.value]?.let { v to it } }.toMap()
    }
}
