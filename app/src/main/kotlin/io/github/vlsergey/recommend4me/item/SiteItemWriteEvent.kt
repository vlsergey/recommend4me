package io.github.vlsergey.recommend4me.item

/**
 * WHAT A SITE SAYS OF THESE ITEMS OF A SOURCE WAS WRITTEN — a page the browser sent, a kept page read again by a new
 * parser, a page a source's job downloaded: told by the source's store alone, before [WorkChangeEvent] of the same
 * items. What is made of the site's own data alone listens to it — the binding of the items, their authors, tags and
 * fandoms to the normalized ones, their texts encoded — and not to [WorkChangeEvent], which an answer of the user, a
 * merge of tags or of universes, a draft of the model tells too, of every work of a tag of hundreds of thousands (user,
 * 2026-11-19: "both of those must listen to: the extension brought a new page").
 */
data class SiteItemWriteEvent(val source: String, val ids: List<String>)
