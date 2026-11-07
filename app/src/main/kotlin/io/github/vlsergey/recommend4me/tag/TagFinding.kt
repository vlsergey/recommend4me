package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import org.springframework.stereotype.Service

/** A tag found by its name ([tag]), or an entry of a catalogue no tag is bound to, offered as a new tag ([entry]). */
class FoundValue(val tag: NamedTag?, val entry: FoundEntry?)

/**
 * THE TAGS FOUND BY A TEXT TYPED, to be given to a work: by their names, then by the meaning of their names, then the
 * entries of the catalogues no tag goes by. A tag the user ignores is never found: it is not offered to add.
 */
@Service
class TagFinding(
    private val tags: Tags,
    private val entries: TagEntries,
    private val phrases: PhraseVectors,
    private val plugins: Plugins,
) {

    /**
     * The tags of the layer found by [query] for a work of [source], at most [limit]: by their names — a name that is
     * the text first, then one beginning with it, then one holding it; of each, the tags a site's tag of [source] of
     * that name holds first, then by any of their names — of every site, the user's, the one shown — the most used
     * first among them; then, while fewer are found, the likest by the meaning of their names.
     */
    fun find(scope: TagScope, source: String, query: String, limit: Int): List<NamedTag> =
        find(scope, tags.naming(scope), source, query, limit)

    /**
     * The tags [find] finds — and, WHEN NO TAG GOES BY THE [query] ITSELF by any of its names, the entries of the
     * catalogues found by it after them: of an entry a tag is bound to, that tag; of one none is, the entry, offered as
     * a new tag (user, 2026-10-16) — made of it when the work is given it ([TagEntries.tagOfEntry]).
     */
    fun findOrNew(scope: TagScope, source: String, query: String, limit: Int): List<FoundValue> {
        val n = tags.naming(scope)
        val found = find(scope, n, source, query, limit)
        val wanted = folded(query)
        val named =
            n.stats.values.any { t ->
                t.key !in n.ignored && (n.allNames(t) + n.shown(t.key)).any { folded(it) == wanted }
            }
        if (named || wanted.length < MIN_ENTRY_QUERY) return found.map { FoundValue(it, null) }
        val have = found.map { it.stats.key }.toHashSet()
        val offered =
            entries.findEntries(scope, query.trim()).mapNotNull { e ->
                when {
                    e.tags.isEmpty() -> FoundValue(null, e)
                    // An entry a tag is bound to: that tag, not a new one
                    e.tags.first().first in have -> null
                    else ->
                        n.stats[e.tags.first().first]
                            ?.takeIf { it.key !in n.ignored }
                            ?.let { FoundValue(n.named(it), null) }
                            ?.also { have += e.tags.first().first }
                }
            }
        return found.map { FoundValue(it, null) } + offered
    }

    private fun folded(name: String) = name.trim().lowercase().replace('ё', 'е')

    private fun find(scope: TagScope, n: Naming, source: String, query: String, limit: Int): List<NamedTag> {
        val wanted = query.trim().lowercase()
        val stats = n.stats.filterKeys { it !in n.ignored }
        val found = LinkedHashMap<String, TagStats>()
        byName(n, stats, source, wanted).take(limit).forEach { found[it.key] = it }
        if (found.size < limit && wanted.isNotEmpty())
            byMeaning(scope, n, stats, query.trim(), found.keys).take(limit - found.size).forEach { found[it.key] = it }
        return found.values.take(limit).map(n::named)
    }

    /**
     * BY HOW CLOSE THE NAME FIRST, THEN BY WHOSE: a tag of the name typed itself, of any site, before one of this site
     * that only holds it — "ER" of ficbook was lost behind the twenty tags of AO3 holding "er" (2026-10-17); of one
     * closeness, the names of this site's tags first, then any name.
     */
    private fun byName(n: Naming, stats: Map<String, TagStats>, source: String, wanted: String): List<TagStats> {
        // How a tag's names hold the text: 0 one is it, 1 one begins with it, 2 one holds it
        fun match(names: List<String>): Int? {
            val lower = names.map { it.lowercase() }
            return when {
                lower.any { it == wanted } -> 0
                lower.any { it.startsWith(wanted) } -> 1
                lower.any { wanted in it } -> 2
                else -> null
            }
        }
        return stats.values
            .mapNotNull { t ->
                val own = match(t.variants.values.filter { it.site.source == source }.map { it.name })
                val any = match(n.allNames(t) + n.shown(t.key))
                listOfNotNull(own, any).minOrNull()?.let { best -> Triple(t, best, if (own == best) 0 else 1) }
            }
            .sortedWith(
                compareBy<Triple<TagStats, Int, Int>> { it.second }
                    .thenBy { it.third }
                    .thenByDescending { it.first.counts.works }
                    .thenBy { it.first.key }
            )
            .map { it.first }
    }

    /** The tags but those [found] already, the likest to the text [text] by the meaning of their names first. */
    private fun byMeaning(
        scope: TagScope,
        n: Naming,
        stats: Map<String, TagStats>,
        text: String,
        found: Set<String>,
    ): List<TagStats> {
        val meaning = TagMeaning(scope, stats, phrases, plugins)
        val target = meaning.ofText(text)
        if (target.isEmpty()) return emptyList()
        return stats.values
            .filter { it.key !in found && (it.variants.isNotEmpty() || n.entriesOf(it.key).isNotEmpty()) }
            .map { it to meaning.likeness(target, it.key) }
            .sortedWith(compareByDescending<Pair<TagStats, Double>> { it.second }.thenBy { it.first.key })
            .map { it.first }
    }

    private companion object {
        /** The fewest letters a name typed is looked for in the catalogues by: fewer find everything. */
        const val MIN_ENTRY_QUERY = 3
    }
}
