import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLinkIcon, Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type ContentTypeInfo, type DuplicateWork, type WorkPairJournalEntry, type WorkPairJournalPage } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { ago, formatDate } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { ITEMS, itemKey } from "@/item/lists";
import { useRefresh } from "@/lib/useRefresh";

/** How many words a page shows, and how many more each "more" adds. */
const PAGE = 50;

/** A word on a pair, as the buttons of the pairs name it: none — taken back. */
type Word = WorkPairJournalEntry["answer"];

/** The key of the words of a pair's buttons ("PairRow.same"), of none the journal's own. */
const WORDS = {
  SAME: "PairRow.same",
  DIFFERENT: "PairRow.different",
  TRANSLATION: "PairRow.translation",
  PARTIAL: "PairRow.partial",
} as const satisfies Record<NonNullable<Word>, string>;

/**
 * THE JOURNAL OF THE WORDS ON PAIRS of works of the type, the newest first: every word said — one work,
 * different, a translation, a part, here or in the window of a work — and every word taken back, with
 * the word the pair had before. The newest word on a pair, while the pair has it, is undone by a press:
 * the pair given back the word before, itself a word of the journal. Read when opened, and as often as
 * chosen; an undoing reads nothing — the word is marked undone where it stands.
 */
export function PairJournal({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("duplicate");
  const [limit, setLimit] = useState(PAGE);
  const [every, setEvery] = useRefresh("duplicates.journal.refresh", 0);
  const [undone, setUndone] = useState<Set<number>>(new Set());
  const queryClient = useQueryClient();
  const journal = useQuery({
    queryKey: ["duplicates", type.id, "journal", limit],
    queryFn: async (): Promise<WorkPairJournalPage> => {
      const result = await api.GET("/api/types/{type}/duplicates/journal", { params: { path: { type: type.id }, query: { offset: 0, limit } } });
      if (!result.data) throw new Error(result.response.statusText || String(result.response.status));
      return result.data;
    },
    refetchInterval: every || false,
  });
  const data = journal.data;
  const undo = async (w: WorkPairJournalEntry) => {
    ensureOk(await api.POST("/api/types/{type}/duplicates/journal/{id}/undo", { params: { path: { type: type.id, id: w.id } } }));
    setUndone((before) => new Set(before).add(w.id));
    // What else shows the works' links and the pairs marked out of date, read when next opened: nothing is read now
    for (const work of [w.first, w.second]) queryClient.invalidateQueries({ queryKey: itemKey(work), refetchType: "none" });
    queryClient.invalidateQueries({ queryKey: [ITEMS], refetchType: "none" });
    queryClient.invalidateQueries({ queryKey: ["duplicates", type.id], refetchType: "none" });
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-sm text-muted-foreground">{t("PairJournal.description")}</p>
        {data && <span className="text-sm text-muted-foreground tabular-nums">{t("PairJournal.total", { count: data.total })}</span>}
        <div className="ml-auto">
          <RefreshSelect every={every} onChange={setEvery} />
        </div>
      </div>
      {journal.isLoading && <p className="text-sm text-muted-foreground">{t("PairJournal.loading")}</p>}
      {journal.isError && <p className="text-sm text-destructive">{t("PairJournal.failed", { error: journal.error.message })}</p>}
      {data && data.words.length === 0 && <p className="text-sm text-muted-foreground">{t("PairJournal.none")}</p>}
      {data && data.words.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {data.words.map((w) => (
            <JournalRow key={w.id} word={w} undone={undone.has(w.id)} undo={() => undo(w)} />
          ))}
        </ul>
      )}
      {data && data.total > data.words.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("PairJournal.more", { count: Math.min(PAGE, data.total - data.words.length) })}
        </Button>
      )}
    </div>
  );
}

/** A word of the journal: when, the word and the word before, the two works, and its undoing while it can be. */
function JournalRow({ word: w, undone, undo }: { word: WorkPairJournalEntry; undone: boolean; undo: () => Promise<void> }) {
  const { t } = useTranslation("duplicate");
  const named = (word: Word) => (word ? t(WORDS[word]) : t("PairJournal.noWord"));
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
      <span className="w-28 shrink-0 text-xs text-muted-foreground tabular-nums" title={formatDate(w.saidAt)}>
        {ago(w.saidAt)}
      </span>
      <span className="flex shrink-0 flex-col">
        <span className="font-semibold">{w.answer ? t(WORDS[w.answer]) : t("PairJournal.taken")}</span>
        <span className="text-xs text-muted-foreground">
          {w.restored ? (
            <span title={t("PairJournal.restoredHint")}>{t("PairJournal.restored")}</span>
          ) : (
            t("PairJournal.before", { word: named(w.before) })
          )}
        </span>
      </span>
      <span className="flex min-w-0 flex-1 basis-full flex-col gap-0.5 sm:basis-0">
        <JournalWork work={w.first} />
        <JournalWork work={w.second} />
      </span>
      {undone ? (
        <span className="text-xs text-muted-foreground">{t("PairJournal.undone")}</span>
      ) : (
        w.undoable && (
          <AsyncButton size="sm" variant="outline" icon={<Undo2Icon />} title={t("PairJournal.undoHint", { word: named(w.before) })} onClick={undo}>
            {t("PairJournal.undo")}
          </AsyncButton>
        )
      )}
    </li>
  );
}

/** A work of a word: its site, its title opening the work, its page on the site; a work no longer there by its key. */
function JournalWork({ work: w }: { work: DuplicateWork }) {
  const { t } = useTranslation("duplicate");
  return (
    <span className="flex min-w-0 items-baseline gap-2">
      <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
        {sourceShort(w.source)}
      </span>
      <a href={itemHref({ source: w.source, item: w.item })} className="min-w-0 truncate underline-offset-2 hover:underline">
        {w.title}
      </a>
      {w.url && (
        <a href={w.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("WorkSide.page")}>
          <ExternalLinkIcon className="size-3.5" />
        </a>
      )}
    </span>
  );
}
