package io.github.vlsergey.recommend4me.work

/**
 * The user put works of the content type [type] together or apart — two items one work or two, works in a series or out
 * of it — or normalized authors were merged or an author of a site taken out of one: what is made of the groups of the
 * works (the normalized series, the manners of the authors and of the series) is to be made again. Of the series and of
 * two items one work or two, the works changed — of every group of the user's that changed, the works two items are of
 * now ([works]); of the authors, the dates of their lists of works tell it ([io.github.vlsergey.recommend4me.author
 * .AuthorWorkDates]), and the normalized authors merged into others are gone ([goneAuthors]).
 */
data class WorkGroupChangeEvent(
    val type: String,
    val works: List<Long> = emptyList(),
    val goneAuthors: List<Long> = emptyList(),
)
