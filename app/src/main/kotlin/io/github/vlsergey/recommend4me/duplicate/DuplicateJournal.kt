package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.api.model.WorkPairJournalEntry
import io.github.vlsergey.recommend4me.api.model.WorkPairJournalPage
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.PairWord
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/** THE JOURNAL OF THE WORDS ON PAIRS of a type as the user looks through it, and a word of it undone. */
@Component
class DuplicateJournal(
    private val stores: Stores,
    private val duplicates: Duplicates,
    private val views: DuplicateViews,
) {
    /**
     * A page of the journal of the words on pairs of the type, the newest first: each word, the word before it, the two
     * works as the pairs show them (a work no longer there by its key) and whether it can be undone — the newest word
     * on its pair and what the pair has now. Null of no such type.
     */
    fun journal(typeId: String, offset: Int, limit: Int): WorkPairJournalPage? {
        val type = stores.type(typeId) ?: return null
        val all = type.links.journal()
        // The newest word of every pair: the first met, the journal the newest first
        val newest = HashSet<Long>()
        all.distinctBy { type.links.ordered(it.first, it.second) }.forEach { newest += it.id }
        val page = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        val now = type.links.words(page.map { it.first to it.second })
        val described = views.describe(type, page.flatMap { listOf(it.first, it.second) })
        fun shown(key: ItemKey) =
            described[key]?.work
                ?: DuplicateWork(
                    source = key.source,
                    item = key.id,
                    title = key.id,
                    url = "",
                    authors = emptyList(),
                    fandoms = emptyList(),
                )
        return WorkPairJournalPage(
            total = all.size,
            words =
                page.map { w ->
                    WorkPairJournalEntry(
                        id = w.id,
                        saidAt = w.saidAt.atOffset(ZoneOffset.UTC),
                        first = shown(w.first),
                        second = shown(w.second),
                        answer = w.answer?.let { WorkPairJournalEntry.Answer.valueOf(it) },
                        before = w.before?.let { WorkPairJournalEntry.Before.valueOf(it) },
                        restored = w.restored,
                        undoable = w.id in newest && w.answer != w.before && now[w.first to w.second] == w.answer,
                    )
                },
        )
    }

    /**
     * The word [id] of the journal undone: the pair given back the word it had before — none, one work, different,
     * related —, itself a word of the journal. False when it is no word of the type, or no longer what the pair has: a
     * newer word on it, or on another pair of their works.
     */
    fun undo(typeId: String, id: Long): Boolean {
        val word = stores.type(typeId)?.let { undoable(it, id) }
        val before = word?.before
        return when {
            word == null -> false
            before == null -> {
                duplicates.unanswer(typeId, word.first, word.second)
                true
            }
            else -> duplicates.answer(typeId, word.first, word.second, DuplicateAnswer.valueOf(before))
        }
    }

    /**
     * The word [id] of the journal of the type when it can be undone: the newest word on its pair, what the pair has
     * now, and other than the word before it; null otherwise.
     */
    private fun undoable(type: TypeStore, id: Long): PairWord? {
        val all = type.links.journal()
        val word = all.firstOrNull { it.id == id } ?: return null
        val pair = type.links.ordered(word.first, word.second)
        val newest = all.first { type.links.ordered(it.first, it.second) == pair }.id == id
        return word.takeIf {
            newest &&
                type.links.word(word.first, word.second, Instant.now()) == word.answer &&
                word.answer != word.before
        }
    }
}
