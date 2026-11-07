package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.refusal.Refusal
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(TagEditing::class.java)

/**
 * THE USER'S WORD ON THE NORMALIZED TAGS: the user merges normalized tags — binds the sites' tags of one to another —
 * takes a site's tag out to a tag of its own, puts tags under others, tells two apart, names a tag, ignores one. The
 * word is written and answered at once; what follows of it is worked out in the background ([TagRenaming.changed]).
 */
@Service
class TagEditing(
    private val tags: Tags,
    private val renaming: TagRenaming,
    private val events: ApplicationEventPublisher,
) {

    /**
     * What the user says the normalized tag [other] is to [tag] — merged into it, under it, above it, different, or
     * nothing — in place of what was said of the two before. Merged, [other] is no more: its sites' tags are bound to
     * [tag], the user's answers on it are answers on [tag] — the newest stands of a work answered on both — and so are
     * its links. A tag is never put above or under one of its own descendants or ancestors.
     */
    fun relate(scope: TagScope, facet: String, tag: String, other: String, relation: TagRelation): NamedTag? {
        refuseUnsaid(tag, other, relation)
        val a = scope.existing(tag)
        val b = scope.existing(other)
        if (a == null || b == null) return null
        val before = tags.itemsOf(scope, facet, listOf(tag, other))
        val now = Instant.now()
        // What was said of the two taken back and the new word said, all or nothing: a refusal leaves them as they were
        scope.tags.transaction { t ->
            val r = t.relations
            r.setParent(b, a, false, now)
            r.setParent(a, b, false, now)
            r.setDistinct(a, b, false, now)
            refuseLoop(TagHierarchy(r.parents()), tag, other, relation)
            when (relation) {
                TagRelation.MERGED -> r.merge(b, a, now)
                TagRelation.CHILD -> r.setParent(b, a, true, now)
                TagRelation.PARENT -> r.setParent(a, b, true, now)
                TagRelation.DIFFERENT -> r.setDistinct(a, b, true, now)
                TagRelation.NONE,
                TagRelation.DESCENDANT,
                TagRelation.ANCESTOR -> Unit
            }
        }
        if (relation == TagRelation.MERGED) moveMergedAnswers(scope, facet)
        log.info("{}: tag {} is {} to {}", scope.id, other, relation, tag)
        renaming.changed(scope, before)
        return tags.one(scope, facet, tag)
    }

    /** A word that is never said refused: of a tag to itself, of one through others — said of the tags between. */
    private fun refuseUnsaid(tag: String, other: String, relation: TagRelation) {
        if (tag == other) throw Refusal.TAG_SELF.refuse(HttpStatus.BAD_REQUEST, "The tag $tag is not related to itself")
        if (relation == TagRelation.DESCENDANT || relation == TagRelation.ANCESTOR) {
            throw Refusal.TAG_THROUGH.refuse(
                HttpStatus.BAD_REQUEST,
                "$relation is read, never said: said of the tags between",
            )
        }
    }

    /** A tag put under one of its own descendants or above one of its ancestors refused: it would loop. */
    private fun refuseLoop(hierarchy: TagHierarchy, tag: String, other: String, relation: TagRelation) {
        if (relation == TagRelation.CHILD && other in hierarchy.ancestors(tag)) {
            throw Refusal.TAG_ABOVE.refuse(
                HttpStatus.BAD_REQUEST,
                "The tag $other is above $tag already: as its child it would be its own parent",
            )
        }
        if (relation == TagRelation.PARENT && other in hierarchy.descendants(tag)) {
            throw Refusal.TAG_BELOW.refuse(
                HttpStatus.BAD_REQUEST,
                "The tag $other is below $tag already: as its parent it would be its own child",
            )
        }
    }

    /**
     * The site's tag [site] bound to the normalized tag [tag] — to a new one of its own when null: taken out of the tag
     * it was. The user's answers stay with the tags they were given on. Returns the tag it is bound to now.
     */
    fun bind(scope: TagScope, facet: String, site: SiteTagRef, tag: String?): NamedTag? {
        val was = scope.tags.bindings()[site]
        val known = tag?.let(scope::existing)
        if (was == null || (tag != null && known == null)) return null
        val before = tags.itemsOf(scope, facet, listOfNotNull(TagRepository.key(was), tag))
        // A new tag of its own and the site's tag bound to it, both or neither
        val to = scope.tags.transaction { t -> (known ?: t.create()).also { t.bind(site, it, Instant.now()) } }
        log.info("{}: {} of {} bound to tag {}, was {}", scope.id, site.tag, site.source, to, was)
        renaming.changed(scope, before)
        return tags.one(scope, facet, TagRepository.key(to))
    }

    /**
     * The name the user gives [tag]; null or blank takes it back — the tag is named by its sites again. Shown over the
     * name worked out, which it is no part of: nothing counted or worked out anew (user, 2026-10-16); the tag's works
     * read again, its name being what they are found by.
     */
    fun name(scope: TagScope, facet: String, tag: String, name: String?): NamedTag? {
        val id = scope.existing(tag) ?: return null
        scope.tags.name(id, name?.trim()?.takeIf { it.isNotEmpty() })
        tags.told(scope, tags.itemsOf(scope, facet, listOf(tag)))
        return tags.one(scope, facet, tag)
    }

    /**
     * Whether the user ignores [tag]: it stays with its works, shown and found, but the model of the grades reads it no
     * more — it learns anew without it.
     */
    fun ignore(scope: TagScope, facet: String, tag: String, ignored: Boolean): NamedTag? {
        val id = scope.existing(tag) ?: return null
        scope.tags.ignore(id, ignored)
        log.info("{}: tag {} {}", scope.id, id, if (ignored) "ignored" else "no longer ignored")
        // The model alone reads it otherwise: the works, their names and the search are as they were
        scope.types.forEach { events.publishEvent(TagsChanged(it)) }
        return tags.one(scope, facet, tag)
    }
}
