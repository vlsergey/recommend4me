package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.plugin.Plugins
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(EntriesAbove::class.java)

/**
 * WHAT THE ENTRIES OF THE CATALOGUES ARE A KIND OF, read of the catalogues and kept ([TagEntryRepository.keepAbove]):
 * "satire" → "humor" → "creative work", [ABOVE_LEVELS] levels up from the entries the tags are bound to, the entries
 * met on the way kept with their names in the user's [languages].
 */
@Component
class EntriesAbove(
    private val plugins: Plugins,
    /** The languages an entry is asked in, the user's first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {

    /**
     * What the entries [from] of [catalogue] are a kind of, read up from them [ABOVE_LEVELS] levels and kept: of an
     * entry read before, what was kept of it — unless [anew], every one asked again and the names of those met on the
     * way too; the entries met on the way kept with their names.
     */
    fun readAbove(scope: TagScope, catalogue: TagCatalogue, from: Collection<String>, anew: Boolean = false) {
        val now = Instant.now()
        val entries = scope.tags.entries
        fun ref(id: String) = TagEntryRef(catalogue.id, id)
        val links = entries.aboveLinks()
        val seen = HashSet(from)
        // The entries kept: the ones bound, the ones above kept on the way
        val known = HashSet(from)
        var frontier = from.distinct()
        var asked = 0
        repeat(ABOVE_LEVELS) {
            if (frontier.isEmpty()) return@repeat
            val read = if (anew) emptySet() else entries.aboveRead(frontier.map(::ref))
            val said = frontier.filter { ref(it) !in read }.takeIf { it.isNotEmpty() }?.let(catalogue::above).orEmpty()
            asked += frontier.count { ref(it) !in read }
            val aboveOf = frontier.associateWith { id ->
                if (ref(id) in read) links[ref(id)].orEmpty().map { it.entry } else said[id].orEmpty()
            }
            // The entries above kept before the links to them: a link is to an entry kept, one the catalogue
            // does not give — gone from it — no link, and no way on from it
            val above = aboveOf.values.flatten().distinct().filter { it !in known }
            val kept = if (anew) emptySet() else entries.keptEntries(above.map(::ref)).keys.map { it.entry }.toSet()
            known += kept
            val missing = above.filter { it !in kept }
            if (missing.isNotEmpty())
                catalogue.entries(missing, languages).forEach {
                    entries.keepEntry(catalogue.id, it, now)
                    known += it.id
                }
            frontier
                .filter { ref(it) !in read }
                .forEach { id -> entries.keepAbove(ref(id), said[id].orEmpty().filter { it in known }, now) }
            frontier = aboveOf.values.flatten().filter { it in known && seen.add(it) }.distinct()
        }
        log.info(
            "{}: what {} entries of {} are a kind of read, {} asked of it, {} met",
            scope.id,
            from.size,
            catalogue.id,
            asked,
            seen.size,
        )
    }

    /**
     * What the entry [entry] of [catalogue] is a kind of, read and kept, as a tag is bound to it: the binding stands
     * without it — a failure is told in the log, the kinds read again from the tag's card.
     */
    // A catalogue is a plugin that asks a site: it may fail any way, and the binding made stands whatever it does
    @Suppress("TooGenericExceptionCaught")
    fun readAboveOf(scope: TagScope, catalogue: TagCatalogue, entry: String) {
        try {
            readAbove(scope, catalogue, listOf(entry))
        } catch (e: Exception) {
            log.warn("{}: what {}:{} is a kind of was not read: {}", scope.id, catalogue.id, entry, e.message)
        }
    }

    /**
     * What the entries bound to the tags are a kind of, of every entry never read: the bindings made before it was
     * read.
     */
    // A catalogue is a plugin that asks a site: one that fails any way leaves the others to be read
    @Suppress("TooGenericExceptionCaught")
    fun readAboveOfBound(scope: TagScope) {
        val bound = scope.tags.entries.entriesOfTags().values.flatten().toSet()
        val read = scope.tags.entries.aboveRead(bound)
        (bound - read)
            .groupBy { it.catalogue }
            .forEach { (id, refs) ->
                val catalogue = plugins.tagCatalogues.firstOrNull { it.id == id } ?: return@forEach
                try {
                    readAbove(scope, catalogue, refs.map { it.entry })
                } catch (e: Exception) {
                    log.warn("{}: what the entries of {} are a kind of was not read: {}", scope.id, id, e.message)
                }
            }
    }

    companion object {
        /**
         * How many levels up what an entry is a kind of is read and offered from: "satire" → "humor" → "creative work"
         * → … — the bound of the reading, not of what is shown of it.
         */
        const val ABOVE_LEVELS = 5
    }
}
