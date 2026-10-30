import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CopyIcon, ExternalLinkIcon, Loader2Icon, RefreshCwIcon, SplitIcon, Undo2Icon, UserIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type ContentTypeInfo, type DuplicatePage, type DuplicatePair, type DuplicateWork } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ago, formatDate, percent } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { ITEMS, itemKey } from "@/item/lists";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/** How many pairs a page shows, and how many more each "more" adds. */
const PAGE = 30;

/** How often the pairs are read again till the user chooses ([RefreshSelect]): never — they change by the user's answers and searches. */
const EVERY_MS = 0;

/** While a search runs, the pairs are asked for again so often: what it found comes when it is done. */
const SEARCHING_MS = 3000;

const duplicatesKey = (type: string) => ["duplicates", type];

/** A row: a pair, by its two works. */
const rowOf = (p: DuplicatePair) => `${p.first.source}/${p.first.item}|${p.second.source}/${p.second.item}`;

/** A row read again the same: its answer. */
const sameRow = (a: DuplicatePair, b: DuplicatePair) => a.answer === b.answer;

/** The pair's two works as the server names them. */
const refs = (p: DuplicatePair) => ({ first: { source: p.first.source, item: p.first.item }, second: { source: p.second.source, item: p.second.item } });

/**
 * THE SCREEN OF THE WORKS THAT MAY BE ONE across the sites of a content type: pairs of works of
 * different sites whose titles and descriptions are alike by their vectors — of every work its two
 * nearest of every other site, the most alike first, none hidden — each to answer on: one work
 * (linked, its grade and interest shared, as the work's window links them) or different works (never
 * offered again). The answered ones apart, their word changed or taken back there. The pairs are
 * looked for in the background, on the first start and when asked anew here.
 */
export function DuplicatesScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("duplicate");
  const [answered, setAnswered] = useState(false);
  const [limit, setLimit] = useState(PAGE);
  const [every, setEvery] = useRefresh("duplicates.refresh", EVERY_MS);
  const queryClient = useQueryClient();
  const list = useQuery({
    queryKey: [...duplicatesKey(type.id), answered, limit],
    queryFn: async (): Promise<DuplicatePage> => {
      const result = await api.GET("/api/types/{type}/duplicates", { params: { path: { type: type.id }, query: { answered, offset: 0, limit } } });
      if (!result.data) throw new Error(result.response.statusText || String(result.response.status));
      return result.data;
    },
    // A search under way: asked again till it is done, whatever is chosen
    refetchInterval: (query) => (query.state.data?.searching ? SEARCHING_MS : every || false),
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.pairs, rowOf, sameRow);
  const shown = calm.shown ?? [];
  /** What else shows the works' links read anew: their dialogs, their cards. */
  const changed = (p: DuplicatePair) => {
    queryClient.invalidateQueries({ queryKey: itemKey(p.first) });
    queryClient.invalidateQueries({ queryKey: itemKey(p.second) });
    queryClient.invalidateQueries({ queryKey: [ITEMS] });
  };
  /** The user's word on a pair: once kept, the row leaves — of the answered ones, it shows the new word. */
  const answer = async (p: DuplicatePair, same: boolean) => {
    ensureOk(await api.PUT("/api/types/{type}/duplicates/answer", { params: { path: { type: type.id } }, body: { ...refs(p), same } }));
    if (answered) queryClient.invalidateQueries({ queryKey: duplicatesKey(type.id) });
    else calm.remove([rowOf(p)]);
    changed(p);
  };
  /** The word taken back: once kept, the row leaves the answered ones. */
  const unanswer = async (p: DuplicatePair) => {
    const { first, second } = refs(p);
    ensureOk(
      await api.DELETE("/api/types/{type}/duplicates/answer", {
        params: { path: { type: type.id }, query: { source: first.source, item: first.item, otherSource: second.source, otherItem: second.item } },
      }),
    );
    calm.remove([rowOf(p)]);
    changed(p);
  };
  const search = async () => {
    ensureOk(await api.POST("/api/types/{type}/duplicates/search", { params: { path: { type: type.id } } }));
    await queryClient.invalidateQueries({ queryKey: duplicatesKey(type.id) });
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
        <Tabs value={answered ? "answered" : "open"} onValueChange={(v) => (setAnswered(v === "answered"), setLimit(PAGE))}>
          <TabsList>
            <TabsTrigger value="open">{t("DuplicatesScreen.open")}</TabsTrigger>
            <TabsTrigger value="answered">{t("DuplicatesScreen.answered")}</TabsTrigger>
          </TabsList>
        </Tabs>
        {data && <span className="text-sm text-muted-foreground tabular-nums">{t("DuplicatesScreen.total", { count: data.total })}</span>}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {data?.searching ? (
            <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
              <Loader2Icon className="size-4 animate-spin" /> {t("DuplicatesScreen.searching")}
            </span>
          ) : (
            data?.searchedAt && (
              <span className="text-xs text-muted-foreground" title={formatDate(data.searchedAt)}>
                {t("DuplicatesScreen.searchedAt", { ago: ago(data.searchedAt), works: data.works ?? 0 })}
              </span>
            )
          )}
          <AsyncButton size="sm" variant="outline" icon={<RefreshCwIcon />} disabled={data?.searching} title={t("DuplicatesScreen.searchHint")} onClick={search}>
            {t("DuplicatesScreen.search")}
          </AsyncButton>
          <RefreshSelect every={every} onChange={setEvery} />
        </div>
      </div>
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("DuplicatesScreen.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("DuplicatesScreen.failed", { error: list.error.message })}</p>}
      {data && shown.length === 0 && !data.searching && (
        <p className="text-sm text-muted-foreground">{t(answered ? "DuplicatesScreen.noneAnswered" : data.searchedAt ? "DuplicatesScreen.none" : "DuplicatesScreen.neverSearched")}</p>
      )}
      {shown.length > 0 && (
        <ul className={cn("flex flex-col gap-2", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
          {shown.map((p) => (
            <PairRow key={rowOf(p)} rowRef={calm.rowRef(rowOf(p))} locked={calm.locked} pair={p} answer={(same) => answer(p, same)} unanswer={() => unanswer(p)} />
          ))}
        </ul>
      )}
      {data && data.total > shown.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("DuplicatesScreen.more", { count: Math.min(PAGE, data.total - shown.length) })}
        </Button>
      )}
    </div>
  );
}

/**
 * A pair: the two works side by side, how alike their titles and descriptions are, whether an author
 * goes by the same name, and the answers — the one given marked, with the taking back beside.
 */
function PairRow({
  pair: p,
  rowRef,
  locked,
  answer,
  unanswer,
}: {
  pair: DuplicatePair;
  rowRef: (e: HTMLElement | null) => void;
  /** The rows moving: the buttons shown disabled, the reason no press is taken. */
  locked: boolean;
  answer: (same: boolean) => Promise<void>;
  unanswer: () => Promise<void>;
}) {
  const { t } = useTranslation("duplicate");
  return (
    <li ref={rowRef} className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        {p.similarity != null && (
          <span className="font-semibold text-foreground tabular-nums" title={t("PairRow.similarityHint")}>
            {percent(p.similarity)}
          </span>
        )}
        {p.title != null && <span className="tabular-nums">{t("PairRow.title", { value: percent(p.title) })}</span>}
        {p.description != null && <span className="tabular-nums">{t("PairRow.description", { value: percent(p.description) })}</span>}
        {p.sameAuthor && (
          <span className="flex items-center gap-1 text-foreground" title={t("PairRow.sameAuthorHint")}>
            <UserIcon className="size-3.5" /> {t("PairRow.sameAuthor")}
          </span>
        )}
        <div className="ml-auto flex flex-wrap gap-1.5">
          <AsyncButton
            size="sm"
            variant={p.answer === "SAME" ? "default" : "outline"}
            icon={<CopyIcon />}
            disabled={locked || p.answer === "SAME"}
            title={t("PairRow.sameHint")}
            onClick={() => answer(true)}
          >
            {t("PairRow.same")}
          </AsyncButton>
          <AsyncButton
            size="sm"
            variant={p.answer === "DIFFERENT" ? "default" : "outline"}
            icon={<SplitIcon />}
            disabled={locked || p.answer === "DIFFERENT"}
            title={t("PairRow.differentHint")}
            onClick={() => answer(false)}
          >
            {t("PairRow.different")}
          </AsyncButton>
          {p.answer && (
            <AsyncButton size="sm" variant="ghost" icon={<Undo2Icon />} disabled={locked} title={t("PairRow.unanswerHint")} onClick={unanswer}>
              {t("PairRow.unanswer")}
            </AsyncButton>
          )}
        </div>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <WorkSide work={p.first} />
        <WorkSide work={p.second} />
      </div>
    </li>
  );
}

/** One work of a pair: its site, its title opening the work, its page on the site, its authors and fandoms, its description folded. */
function WorkSide({ work: w }: { work: DuplicateWork }) {
  const { t } = useTranslation("duplicate");
  const [whole, setWhole] = useState(false);
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex items-baseline gap-2">
        <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
          {sourceShort(w.source)}
        </span>
        <a href={itemHref({ source: w.source, item: w.item })} className="min-w-0 font-semibold underline-offset-2 hover:underline">
          {w.title}
        </a>
        <a href={w.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("WorkSide.page")}>
          <ExternalLinkIcon className="size-3.5" />
        </a>
      </div>
      {(w.authors.length > 0 || w.fandoms.length > 0) && (
        <div className="text-xs text-muted-foreground">{[w.authors.join(", "), w.fandoms.join(", ")].filter(Boolean).join(" · ")}</div>
      )}
      {w.description && (
        // Text to select and copy: unfolded or folded by a press that selects nothing
        <div
          className={cn("cursor-pointer text-sm whitespace-pre-line select-text text-muted-foreground", !whole && "line-clamp-4")}
          title={t(whole ? "WorkSide.fold" : "WorkSide.unfold")}
          onClick={() => {
            if (!window.getSelection()?.toString()) setWhole(!whole);
          }}
        >
          {w.description}
        </div>
      )}
    </div>
  );
}
