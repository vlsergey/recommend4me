package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.plugin.Plugins
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(TagEntries::class.java)

/**
 * THE ENTRIES OF THE CATALOGUES THE TAGS ARE BOUND TO, by the user's word: an entry found by a text and bound to a tag,
 * unbound, read of its catalogue again; a new tag of an entry, put above other tags or given to a work. An entry names
 * the tag in every language of the user ([languages]), and its other names are the tag's too.
 */
@Service
class TagEntries(
    private val tags: Tags,
    private val renaming: TagRenaming,
    private val above: EntriesAbove,
    private val plugins: Plugins,
    /** The languages a tag's entry is asked in, the user's first: its label in the first that has one names the tag. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {

    /**
     * The entries every catalogue of tags finds by [text] — an id or a page's address is the entry itself — each with
     * the tags bound to it already.
     */
    fun findEntries(scope: TagScope, text: String): List<FoundEntry> {
        if (text.isBlank()) return emptyList()
        val bound = HashMap<TagEntryRef, MutableList<String>>()
        scope.tags.entries.entriesOfTags().forEach { (id, refs) ->
            refs.forEach { bound.getOrPut(it) { ArrayList() } += TagRepository.key(id) }
        }
        val found = plugins.tagCatalogues.flatMap { c -> c.find(text, languages).map { c.id to it } }
        val names = scope.tags.stats.shown(found.flatMap { (c, e) -> bound[TagEntryRef(c, e.id)].orEmpty() })
        return found.map { (c, e) ->
            FoundEntry(c, e, bound[TagEntryRef(c, e.id)].orEmpty().map { it to (names[it] ?: it) })
        }
    }

    /**
     * The tag [tag] bound to the entry [entry] of [catalogue] besides the ones it is — the entry read of the catalogue
     * now and kept; the label of the one bound first the tag's name unless the user named it — or, [bound] false, no
     * more to it: its name alone worked out again, of the statistics kept ([entriesChanged]). The tag's works are read
     * again: its name is what the model reads.
     */
    fun bindEntry(
        scope: TagScope,
        facet: String,
        tag: String,
        catalogue: String,
        entry: String,
        bound: Boolean,
    ): NamedTag? {
        val id = scope.existing(tag) ?: return null
        val now = Instant.now()
        if (!bound) {
            scope.tags.entries.bindEntry(id, TagEntryRef(catalogue, entry), false, now)
            log.info("{}: tag {} bound no more to {}:{}", scope.id, tag, catalogue, entry)
        } else {
            val source = catalogueOf(catalogue)
            val read = entryOf(source, entry)
            scope.tags.transaction { t ->
                t.entries.keepEntry(catalogue, read, now)
                t.entries.bindEntry(id, TagEntryRef(catalogue, read.id), true, now)
            }
            log.info(
                "{}: tag {} bound to {}:{} ({})",
                scope.id,
                tag,
                catalogue,
                read.id,
                languages.firstNotNullOfOrNull { read.labels[it] },
            )
            // What it is a kind of: the binding stands without it, read again from the tag's card
            above.readAboveOf(scope, source, read.id)
        }
        entriesChanged(scope, facet, tag)
        return tags.one(scope, facet, tag)
    }

    /**
     * The entries [tag] is bound to read of their catalogues again: their names and descriptions, and what they are a
     * kind of, every level up asked again. What was kept stays shown till then.
     */
    fun refreshEntries(scope: TagScope, facet: String, tag: String): NamedTag? {
        val id = scope.existing(tag) ?: return null
        val now = Instant.now()
        scope.tags.entries
            .entriesOfTags()[id]
            .orEmpty()
            .groupBy { it.catalogue }
            .forEach { (catalogueId, refs) ->
                val catalogue = catalogueOf(catalogueId)
                val ids = refs.map { it.entry }
                catalogue.entries(ids, languages).forEach { scope.tags.entries.keepEntry(catalogueId, it, now) }
                above.readAbove(scope, catalogue, ids, anew = true)
            }
        log.info("{}: the entries of tag {} read again", scope.id, tag)
        entriesChanged(scope, facet, tag)
        return tags.one(scope, facet, tag)
    }

    /**
     * A new tag bound to the entry [entry] of [catalogue] — named by its label — or the tag [existing] bound to it
     * besides its own, put above the tags [children]. Returns it.
     */
    fun createAbove(
        scope: TagScope,
        facet: String,
        catalogue: String,
        entry: String,
        children: List<String>,
        existing: String? = null,
    ): NamedTag? {
        val ids = children.map(scope::existing)
        val upper = existing?.let(scope::existing)
        if (null in ids || (existing != null && upper == null)) return null
        val read = entryOf(catalogueOf(catalogue), entry)
        val before = tags.itemsOf(scope, facet, children + listOfNotNull(existing))
        val now = Instant.now()
        // The tag, its entry and its place above the children, all or nothing; the catalogue asked before
        val id =
            scope.tags.transaction { t ->
                val made = upper ?: t.create()
                t.entries.keepEntry(catalogue, read, now)
                t.entries.bindEntry(made, TagEntryRef(catalogue, read.id), true, now)
                ids.filterNotNull().forEach { t.relations.setParent(it, made, true, now) }
                made
            }
        log.info(
            "{}: tag {} {} {}:{} above {}",
            scope.id,
            id,
            if (upper == null) "made of" else "bound to",
            catalogue,
            read.id,
            children,
        )
        renaming.changed(scope, before)
        return tags.one(scope, facet, TagRepository.key(id))
    }

    /**
     * The tag of the entry [entry] of [catalogue]: the one bound to it, or a new one made of it — named by its label,
     * bound to it, what it is a kind of read — for a work the user gives it.
     */
    fun tagOfEntry(scope: TagScope, facet: String, catalogue: String, entry: String): String {
        val ref = TagEntryRef(catalogue, entry)
        scope.tags.entries
            .entriesOfTags()
            .entries
            .firstOrNull { (_, refs) -> ref in refs }
            ?.let {
                return TagRepository.key(it.key)
            }
        val made =
            createAbove(scope, facet, catalogue, entry, emptyList())
                ?: throw NoSuchElementException("$catalogue has no $entry")
        plugins.tagCatalogues.firstOrNull { it.id == catalogue }?.let { above.readAboveOf(scope, it, entry) }
        // Named by the entry's label at once, as a binding is: shown so beside the work
        entriesChanged(scope, facet, made.stats.key)
        return made.stats.key
    }

    /** The catalogue of tags [id]; none such — refused as not found. */
    private fun catalogueOf(id: String): TagCatalogue =
        plugins.tagCatalogues.firstOrNull { it.id == id } ?: throw NoSuchElementException("No catalogue of tags $id")

    /** The entry [entry] as [catalogue] gives it now; none such — refused as not found. */
    private fun entryOf(catalogue: TagCatalogue, entry: String): TagEntry =
        catalogue.entries(listOf(entry), languages).firstOrNull()
            ?: throw NoSuchElementException("${catalogue.id} has no $entry")

    /**
     * The entries of ONE TAG changed — bound, unbound, read again: ITS NAME ALONE worked out again, of the statistics
     * kept (user, 2026-10-16) — nothing counted anew, no other tag's name touched, the queue of the computations not
     * waited for. Its works read again, as of any change of a tag.
     */
    private fun entriesChanged(scope: TagScope, facet: String, tag: String) {
        val n = tags.naming(scope)
        val id = TagRepository.idOf(tag)
        val stats = n.stats[tag]
        if (id != null && stats != null)
            scope.tags.stats.replaceCalculated(mapOf(id to TagNaming.name(stats, null, n.cataloguedOf(tag))))
        tags.told(scope, tags.itemsOf(scope, facet, listOf(tag)))
    }
}
