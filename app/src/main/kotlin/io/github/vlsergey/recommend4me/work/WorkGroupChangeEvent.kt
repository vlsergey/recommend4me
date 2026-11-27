package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.author.SiteAuthorRef

/**
 * The user put works of the content type [type] together or apart — two items one work or two, works in a series or out
 * of it — or normalized authors were merged or an author of a site taken out of one: what is made of the groups of the
 * works (the manners of the authors and of the series) is to be made again. Of the authors, those of the sites of every
 * normalized author whose works changed ([authors]); of the series, the works of every group of the user's that changed
 * ([works]); of two items one work or two, neither — the items' work changed ([WorkMembership]).
 */
data class WorkGroupChangeEvent(
    val type: String,
    val authors: List<SiteAuthorRef> = emptyList(),
    val works: List<Long> = emptyList(),
)
