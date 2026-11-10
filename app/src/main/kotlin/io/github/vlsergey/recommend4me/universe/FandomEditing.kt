package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(FandomEditing::class.java)

/**
 * THE USER'S WORD ON THE UNIVERSES — THE NORMALIZED FANDOMS: the user merges universes — binds the sites' fandoms of
 * one to another — takes a site's fandom out to a universe of its own, puts universes under others, tells two apart,
 * names one. The word is written and answered at once; every work of the sites' fandoms is drafted again after it
 * ([io.github.vlsergey.recommend4me.draft.LayerDrafts]), told by a change of the works of every source.
 */
@Service
class FandomEditing(
    private val stores: Stores,
    private val normalization: FandomNormalization,
    private val events: ApplicationEventPublisher,
) {
    private fun dictionary(): UniverseRepository = stores.withUniverses().first().universes

    private fun sources(): List<SourceStore> = stores.withUniverses().flatMap { it.sources }

    /**
     * What the user says the universe [other] is to [universe] — merged into it, under it, above it, different, or
     * nothing — in place of what was said of the two before. Merged, [other] is no more: its sites' fandoms, entries
     * and links are [universe]'s, the user's answers on it answers on [universe] — the newest stands of a work answered
     * on both. A universe is never put above or under one of its own descendants or ancestors. False of no such
     * universe.
     */
    fun relate(universe: Long, other: Long, relation: UniverseRelation): Boolean {
        refuseUnsaid(universe, other, relation)
        val fandoms = dictionary().fandoms
        if (!fandoms.exists(universe) || !fandoms.exists(other)) return false
        val now = Instant.now()
        // What was said of the two taken back and the new word said, all or nothing: a refusal leaves them as they were
        fandoms.transaction { f ->
            f.relations.unlink(other, universe)
            f.relations.unlink(universe, other)
            f.relations.forgetApart(universe, other)
            refuseLoop(FandomDictionary.load(f), universe, other, relation)
            when (relation) {
                UniverseRelation.MERGED -> f.merge(other, universe, now)
                UniverseRelation.CHILD -> f.relations.link(other, universe, now)
                UniverseRelation.PARENT -> f.relations.link(universe, other, now)
                UniverseRelation.DIFFERENT -> f.relations.tellApart(universe, other, now)
                UniverseRelation.NONE,
                UniverseRelation.DESCENDANT,
                UniverseRelation.ANCESTOR -> Unit
            }
        }
        if (relation == UniverseRelation.MERGED) merged(other, universe)
        log.info("Universe {} is {} to {}", other, relation, universe)
        changed(listOf(universe))
        return true
    }

    /**
     * The merged universe [from] gone: the user's answers on it moved to [into] in every source — of a work answered on
     * both the newest standing — and it removed; the works whose answers moved told.
     */
    private fun merged(from: Long, into: Long) {
        sources().forEach { s ->
            val moved =
                s.corrections.moveFacetValues(
                    UniverseFacet.KEY,
                    listOf(FandomRepository.value(from)),
                    FandomRepository.value(into),
                )
            if (moved.isNotEmpty()) events.publishEvent(ItemsChanged(s.id, moved.toList()))
        }
    }

    /** A word that is never said refused: of a universe to itself, of one through others — said of those between. */
    private fun refuseUnsaid(universe: Long, other: Long, relation: UniverseRelation) {
        if (universe == other)
            throw Refusal.UNIVERSE_SELF.refuse(
                HttpStatus.BAD_REQUEST,
                "The universe $universe is not related to itself",
            )
        if (relation == UniverseRelation.DESCENDANT || relation == UniverseRelation.ANCESTOR) {
            throw Refusal.UNIVERSE_THROUGH.refuse(
                HttpStatus.BAD_REQUEST,
                "$relation is read, never said: said of the universes between",
            )
        }
    }

    /** A universe put under one of its own descendants or above one of its ancestors refused: it would loop. */
    private fun refuseLoop(dictionary: FandomDictionary, universe: Long, other: Long, relation: UniverseRelation) {
        if (relation == UniverseRelation.CHILD && other in dictionary.ancestors(universe)) {
            throw Refusal.UNIVERSE_ABOVE.refuse(
                HttpStatus.BAD_REQUEST,
                "The universe $other is above $universe already: as its child it would be its own parent",
            )
        }
        if (relation == UniverseRelation.PARENT && other in dictionary.descendants(universe)) {
            throw Refusal.UNIVERSE_BELOW.refuse(
                HttpStatus.BAD_REQUEST,
                "The universe $other is below $universe already: as its parent it would be its own child",
            )
        }
    }

    /**
     * The site's fandom [site] bound to the universe [universe] — to a new one of its own when null: taken out of the
     * universe it was, and put under it when [under] (a part of a franchise: its works of the franchise too). The
     * user's answers stay with the universes they were given on. The universe it is bound to now; null of a site's
     * fandom not bound, or of no such universe.
     */
    fun bind(site: SiteFandomRef, universe: Long?, under: Boolean = false): Long? {
        val fandoms = dictionary().fandoms
        val was = fandoms.sites.fandomOf(site)
        if (was == null || (universe != null && !fandoms.exists(universe))) return null
        val now = Instant.now()
        // A new universe of its own and the site's fandom bound to it — put under the one it leaves, when [under] — all
        // or nothing
        val to = fandoms.transaction { f ->
            (universe ?: f.create(now)).also {
                f.sites.bind(site, it, now)
                if (universe == null && under) f.relations.link(it, was, now)
            }
        }
        log.info("{} of {} bound to universe {}, was {}", site.key, site.source, to, was)
        changed(listOf(was, to))
        return to
    }

    /** The name the user gives [universe]; null or blank takes it back. False of no such universe. */
    fun name(universe: Long, name: String?): Boolean {
        val fandoms = dictionary().fandoms
        if (!fandoms.exists(universe)) return false
        fandoms.name(universe, name?.trim()?.takeIf { it.isNotEmpty() })
        changed(emptyList())
        return true
    }

    /**
     * The universes [renamed] named anew of their sites' fandoms, and every source of the universes told: its works are
     * drafted again, and read again by what they are named and found by.
     */
    private fun changed(renamed: List<Long>) {
        if (renamed.isNotEmpty()) normalization.rename(renamed.filter { dictionary().fandoms.exists(it) })
        sources().forEach { events.publishEvent(ItemsChanged(it.id, emptyList())) }
    }
}
