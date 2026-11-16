package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.NumberJoin
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale

/**
 * The numbers of every content type: what any site of any kind of works may tell alike — one number to sort, filter and
 * compare by across the sites, whatever each calls it.
 */
object StandardNumbers {
    /** How many other people liked the work: a site's likes, its kudos, its "liked it". */
    val LIKES = StandardNumber("likes", NumberScale.LOG, sortable = true, role = NumberRole.APPROVAL)

    /** How many times the work was opened: a site's views, its hits. */
    val VIEWS = StandardNumber("views", NumberScale.LOG, sortable = true, role = NumberRole.REACH)

    /** How long the work's text is, in words. */
    val WORDS = StandardNumber("words", NumberScale.LOG, sortable = true, role = NumberRole.SIZE)

    /** How long the work's text is, in characters, the spaces counted. */
    val CHARS = StandardNumber("chars", NumberScale.LOG, sortable = true, role = NumberRole.SIZE)

    /**
     * Whether the author closed the work's comments to the public — author.today's "комментировать это произведение
     * только своим друзьям": 1 or 0 of an item; of a work, 1 where every site that can close them has them closed, 0
     * where none, 0.5 where some ([NumberJoin.AGREEMENT]). An item whose site did not show it has none.
     */
    val COMMENTS_CLOSED = StandardNumber("commentsClosed", NumberScale.LINEAR, join = NumberJoin.AGREEMENT)
}
