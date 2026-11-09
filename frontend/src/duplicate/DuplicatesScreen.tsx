import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDownIcon, CopyIcon, EqualIcon, EqualNotIcon, LanguagesIcon, LibraryBigIcon, Loader2Icon, PuzzleIcon, RefreshCwIcon, Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type ContentTypeInfo, type DuplicatePage, type DuplicatePair, type DuplicateSort } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ago, formatDate } from "@/i18n/format";
import { ITEMS, itemKey } from "@/item/lists";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { usePref } from "@/lib/usePref";
import { PairFacts, WorkSide } from "./PairCompare";
import { PairJournal } from "./PairJournal";
import { afterWord, sharing } from "./pairWorks";
import { useRefresh } from "@/lib/useRefresh";
import { useWork } from "@/workload/useWork";
import { cn } from "@/lib/utils";

/** How many pairs a page shows, and how many more each "more" adds. */
const PAGE = 30;

/** How often the pairs are read again till the user chooses ([RefreshSelect]): never — they change by the user's answers and searches. */
const EVERY_MS = 0;


const duplicatesKey = (type: string) => ["duplicates", type];

/** A row: a pair, by its two works. */
const rowOf = (p: DuplicatePair) => `${p.first.source}/${p.first.item}|${p.second.source}/${p.second.item}`;

/** A row read again the same: its answer. */
const sameRow = (a: DuplicatePair, b: DuplicatePair) => a.answer === b.answer;

/** The user's word on a pair. */
type Verdict = NonNullable<DuplicatePair["answer"]>;

/** The answers of the buttons: one work (=), different works (≠). */
const VERDICTS: { verdict: Verdict; icon: React.ReactNode; word: "same" | "different" }[] = [
  { verdict: "SAME", icon: <EqualIcon />, word: "same" },
  { verdict: "DIFFERENT", icon: <EqualNotIcon />, word: "different" },
];

/** The answers of related works, behind a drop-down beside the buttons: a translation, a part, one series. */
const RELATED: { verdict: Verdict; icon: React.ReactNode; word: "translation" | "partial" | "series" }[] = [
  { verdict: "TRANSLATION", icon: <LanguagesIcon />, word: "translation" },
  { verdict: "PARTIAL", icon: <PuzzleIcon />, word: "partial" },
  { verdict: "SERIES", icon: <LibraryBigIcon />, word: "series" },
];

/**
 * What the chance a pair is one work is multiplied by, of the work it was found for: the formula of the list first, the default; or
 * the chance of one series alone; or, of the pairs in doubt, those nearest where the answers change, of one work or of one series.
 */
const SORTS: DuplicateSort[] = ["FORMULA", "INTEREST", "GRADE", "SIZE", "CHANCE", "SERIES", "UNCERTAIN", "UNCERTAIN_SERIES"];

/** The sorts whose order says nothing of the work, or is a chance shown already: no priority shown. */
const NO_PRIORITY: DuplicateSort[] = ["SERIES", "UNCERTAIN", "UNCERTAIN_SERIES"];

/** The answer of a pair the list is chosen by: a word, or none ("NONE"). */
type Shown = Verdict | "NONE";

/** The answers the pairs shown are chosen by, each a checkbox: none first, then the words, by the words of their buttons. */
const SHOWN = [
  { shown: "NONE" as Shown, word: "DuplicatesScreen.open" as const },
  ...[...VERDICTS, ...RELATED].map((v) => ({ shown: v.verdict as Shown, word: `PairRow.${v.word}` as const })),
];

/** The pair's two works as the server names them. */
const refs = (p: DuplicatePair) => ({ first: { source: p.first.source, item: p.first.item }, second: { source: p.second.source, item: p.second.item } });

/**
 * THE SCREEN OF THE WORKS THAT MAY BE ONE across the sites of a content type: of every work, the work
 * of every other site likeliest one work with it — compared by every field every site has alike, the
 * text aside — none hidden; ordered by the chance the pair is one work times what the chosen sort says
 * of the work the pair was found for — its score by the formula of the list (the default), its chance
 * of interest, its predicted grade, or nothing — the sort kept in the browser. Each to answer on:
 * one work (linked, its grade and interest shared, as the work's window links them), different works,
 * or — behind a drop-down — related ones: a translation, a part (not linked, each its own grade); never
 * offered again. The answers of one work and different works teach the model of the chances, fitted
 * again a few seconds after them. The pairs shown are chosen by their answer — none, or any of the words,
 * each a checkbox kept in the browser: the pairs said different alone, by the chance of one series, say —
 * a word changed or taken back where it stands; every word said and taken back in the journal, the newest undone there ([PairJournal]). The pairs are looked for in
 * the background, on the first start and when asked anew here.
 */
export function DuplicatesScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("duplicate");
  const [tab, setTab] = useState<"pairs" | "journal">("pairs");
  const [limit, setLimit] = useState(PAGE);
  const [every, setEvery] = useRefresh("duplicates.refresh", EVERY_MS);
  const [sort, setSort] = usePref<DuplicateSort>("duplicates.sort", "FORMULA");
  // The answers the pairs shown are chosen by: the pairs not answered on at first
  const [chosen, setChosen] = usePref<Shown[]>("duplicates.shown", ["NONE"]);
  const open = chosen.includes("NONE");
  const answers = chosen.filter((c): c is Verdict => c !== "NONE");
  const queryClient = useQueryClient();
  // The search, a work of the queue: its state read again while it is to come, the pairs read once it has ended —
  // never while it runs (user, 2026-11-07)
  const search = useWork({ what: "duplicates", type: type.id }, () => queryClient.invalidateQueries({ queryKey: duplicatesKey(type.id) }));
  const searching = search.busy;
  /** When the last word was kept ([settle]): a list read that began before it may not have it. */
  const wordKept = useRef(0);
  const list = useQuery({
    queryKey: [...duplicatesKey(type.id), open, answers, limit, sort],
    enabled: tab !== "journal",
    queryFn: async ({ signal }): Promise<DuplicatePage> => {
      // A list read while a word was being kept may hold the pair as it was, the row answered on brought back:
      // read again till the list began after every word kept — the list read in the background while a search
      // runs (user, 2026-11-07)
      for (;;) {
        const began = performance.now();
        const result = await api.GET("/api/types/{type}/duplicates", { params: { path: { type: type.id }, query: { open, answers, offset: 0, limit, sort } }, signal });
        if (!result.data) throw new Error(result.response.statusText || String(result.response.status));
        if (wordKept.current < began) return result.data;
      }
    },
    refetchInterval: every || false,
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.pairs, rowOf, sameRow);
  const shown = calm.shown ?? [];
  /** What else shows the links of the works of the [pairs] marked out of date, read when next opened: nothing is read now. */
  const changed = (pairs: DuplicatePair[]) => {
    pairs.forEach((p) => {
      queryClient.invalidateQueries({ queryKey: itemKey(p.first), refetchType: "none" });
      queryClient.invalidateQueries({ queryKey: itemKey(p.second), refetchType: "none" });
    });
    queryClient.invalidateQueries({ queryKey: [ITEMS], refetchType: "none" });
  };
  /**
   * The word [verdict] on [p] (none: taken back) kept, shown in the pairs read before. A word is on the two
   * works, so every pair of them shown takes it at once ([afterWord]): an answer the list is chosen by
   * ([chosen]), every such row shows it where it stands; another, every such row leaves, the works of the rest as the word left them. Nothing read again for it: the
   * pairs are read as often as chosen ([RefreshSelect]) — a list read after every answer moved the rows,
   * locking them, between two presses of the user (user, 2026-10-30).
   */
  const settle = (p: DuplicatePair, verdict: Verdict | undefined) => {
    wordKept.current = performance.now();
    const rows = calm.shown ?? [];
    // The rows whose answer the word changed to one the list is not chosen by leave
    const after = afterWord(p, verdict, rows);
    const leaving = rows.filter((q, i) => after[i].answer !== q.answer && !chosen.includes(after[i].answer ?? "NONE"));
    calm.change((list) => afterWord(p, verdict, list));
    if (leaving.length > 0) calm.remove(leaving.map(rowOf));
    changed(sharing(p, rows));
  };
  const answer = async (p: DuplicatePair, verdict: Verdict) => {
    ensureOk(await api.PUT("/api/types/{type}/duplicates/answer", { params: { path: { type: type.id } }, body: { ...refs(p), verdict } }));
    settle(p, verdict);
  };
  const unanswer = async (p: DuplicatePair) => {
    const { first, second } = refs(p);
    ensureOk(
      await api.DELETE("/api/types/{type}/duplicates/answer", {
        params: { path: { type: type.id }, query: { source: first.source, item: first.item, otherSource: second.source, otherItem: second.item } },
      }),
    );
    settle(p, undefined);
  };
  const searchAnew = async () => {
    ensureOk(await api.POST("/api/types/{type}/duplicates/search", { params: { path: { type: type.id } } }));
    search.asked();
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-4">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <CopyIcon className="size-5" /> {t("DuplicatesScreen.title")}
        </h1>
        <p className="text-sm text-muted-foreground">{t("DuplicatesScreen.description")}</p>
      </header>
      <div className="flex flex-wrap items-center gap-2">
        <Tabs
          value={tab}
          onValueChange={(v) => {
            setTab(v as typeof tab);
            setLimit(PAGE);
          }}
        >
          <TabsList>
            <TabsTrigger value="pairs">{t("DuplicatesScreen.pairs")}</TabsTrigger>
            <TabsTrigger value="journal" title={t("DuplicatesScreen.journalHint")}>
              {t("DuplicatesScreen.journal")}
            </TabsTrigger>
          </TabsList>
        </Tabs>
        {tab !== "journal" && (
          <>
            {data && <span className="text-sm text-muted-foreground tabular-nums">{t("DuplicatesScreen.total", { count: data.total })}</span>}
            <label className="flex items-center gap-1.5 text-xs text-muted-foreground" title={t("DuplicatesScreen.sortHint")}>
              {t("DuplicatesScreen.sort")}
              <select
                value={sort}
                onChange={(e) => {
                  setSort(e.target.value as DuplicateSort);
                  setLimit(PAGE);
                }}
                className="h-8 rounded-md border border-input bg-transparent px-1.5 text-sm text-foreground outline-none focus-visible:border-ring dark:bg-input/30"
              >
                {SORTS.map((s) => (
                  <option key={s} value={s}>
                    {t(`DuplicatesScreen.sortBy.${s}`)}
                  </option>
                ))}
              </select>
            </label>
            <div className="ml-auto flex flex-wrap items-center gap-2">
              {searching ? (
                <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
                  <Loader2Icon className="size-4 animate-spin" /> {t("DuplicatesScreen.pending")}
                </span>
              ) : (
                data?.searchedAt && (
                  <span className="text-xs text-muted-foreground" title={formatDate(data.searchedAt)}>
                    {t("DuplicatesScreen.searchedAt", { ago: ago(data.searchedAt), works: data.works ?? 0 })}
                    {" · "}
                    <span title={t(data.learnt ? "DuplicatesScreen.learntHint" : "DuplicatesScreen.notLearntHint")}>
                      {t(data.learnt ? "DuplicatesScreen.learnt" : "DuplicatesScreen.notLearnt")}
                    </span>
                  </span>
                )
              )}
              <AsyncButton size="sm" variant="outline" icon={<RefreshCwIcon />} disabled={searching} title={t("DuplicatesScreen.searchHint")} onClick={searchAnew}>
                {t("DuplicatesScreen.search")}
              </AsyncButton>
              <RefreshSelect every={every} onChange={setEvery} />
            </div>
          </>
        )}
      </div>
      {tab === "pairs" && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground" title={t("DuplicatesScreen.shownHint")}>
          {t("DuplicatesScreen.shown")}
          {SHOWN.map(({ shown: c, word }) => (
            <label key={c} className="flex items-center gap-1">
              <input
                type="checkbox"
                checked={chosen.includes(c)}
                onChange={() => {
                  setChosen(chosen.includes(c) ? chosen.filter((v) => v !== c) : SHOWN.map((a) => a.shown).filter((v) => v === c || chosen.includes(v)));
                  setLimit(PAGE);
                }}
                className="size-3.5"
              />
              {t(word)}
            </label>
          ))}
        </div>
      )}
      {tab === "journal" ? (
        <PairJournal type={type} />
      ) : (
        <>
          {list.isLoading && <p className="text-sm text-muted-foreground">{t("DuplicatesScreen.loading")}</p>}
          {list.isError && <p className="text-sm text-destructive">{t("DuplicatesScreen.failed", { error: list.error.message })}</p>}
          {data && shown.length === 0 && !searching && (
            <p className="text-sm text-muted-foreground">{t(!data.searchedAt ? "DuplicatesScreen.neverSearched" : chosen.length === 1 && open ? "DuplicatesScreen.none" : "DuplicatesScreen.noneChosen")}</p>
          )}
          {shown.length > 0 && (
            <ul className={cn("flex flex-col gap-2", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
              {shown.map((p) => (
                <PairRow key={rowOf(p)} rowRef={calm.rowRef(rowOf(p))} locked={calm.locked} pair={p} sort={sort} answer={(verdict) => answer(p, verdict)} unanswer={() => unanswer(p)} />
              ))}
            </ul>
          )}
          {data && data.total > shown.length && (
            <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
              {t("DuplicatesScreen.more", { count: Math.min(PAGE, data.total - shown.length) })}
            </Button>
          )}
        </>
      )}
    </div>
  );
}

/**
 * A pair: the two works side by side, the chance they are one work, how alike each layer of theirs is,
 * whether the dictionary of authors gives them one author, and the answers — the one given marked, with the taking back beside.
 */
function PairRow({
  pair: p,
  rowRef,
  locked,
  sort,
  answer,
  unanswer,
}: {
  pair: DuplicatePair;
  rowRef: (e: HTMLElement | null) => void;
  /** Of the pairs in doubt the order says nothing of the work, of one series it is a chance shown: no priority shown ([NO_PRIORITY]). */
  sort: DuplicateSort;
  /** The rows moving: the buttons shown disabled, the reason no press is taken. */
  locked: boolean;
  answer: (verdict: Verdict) => Promise<void>;
  unanswer: () => Promise<void>;
}) {
  const { t } = useTranslation("duplicate");
  return (
    <li ref={rowRef} className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <PairFacts pair={p} priority={NO_PRIORITY.includes(sort) ? undefined : sort} />
        <div className="ml-auto flex flex-wrap gap-1.5">
          {VERDICTS.map(({ verdict, icon, word }) => (
            <AsyncButton
              key={verdict}
              size="sm"
              variant={p.answer === verdict ? "default" : "outline"}
              icon={icon}
              disabled={locked || p.answer === verdict}
              title={t(`PairRow.${word}Hint`)}
              onClick={() => answer(verdict)}
            >
              {t(`PairRow.${word}`)}
            </AsyncButton>
          ))}
          {p.derived && (
            <span className="self-center text-xs text-muted-foreground" title={t("PairRow.derivedHint")}>
              {t("PairRow.derived")}
            </span>
          )}
          <Related answered={p.answer} locked={locked} answer={answer} />
          {/* Always in its place, of no answer disabled: a word given moves no button */}
          <AsyncButton size="sm" variant="ghost" icon={<Undo2Icon />} disabled={locked || !p.answer} title={t("PairRow.unanswerHint")} onClick={unanswer}>
            {t("PairRow.unanswer")}
          </AsyncButton>
        </div>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <WorkSide work={p.first} />
        <WorkSide work={p.second} />
      </div>
    </li>
  );
}

/**
 * The answers of related works — a translation, a part — behind a drop-down: not beside the buttons
 * of one work and different works, which most pairs take. The one given names the drop-down, marked;
 * the drop-down is at work while its own answer is.
 */
function Related({ answered, locked, answer }: { answered?: Verdict; locked: boolean; answer: (verdict: Verdict) => Promise<void> }) {
  const { t } = useTranslation("duplicate");
  const [busy, setBusy] = useState(false);
  const given = RELATED.find((r) => r.verdict === answered);
  const choose = async (verdict: Verdict) => {
    setBusy(true);
    try {
      await answer(verdict);
    } finally {
      setBusy(false);
    }
  };
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button size="sm" variant={given ? "default" : "outline"} disabled={locked || busy} title={t("PairRow.relatedHint")} />}
      >
        {busy ? <Loader2Icon className="animate-spin" /> : given?.icon}
        {given ? t(`PairRow.${given.word}`) : t("PairRow.related")}
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-auto">
        {RELATED.map(({ verdict, icon, word }) => (
          <DropdownMenuItem key={verdict} disabled={answered === verdict} title={t(`PairRow.${word}Hint`)} onClick={() => void choose(verdict)}>
            {icon} {t(`PairRow.${word}`)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
