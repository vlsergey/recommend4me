import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import { InboxIcon, Loader2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, refKey, unwrap, type ContentTypeInfo, type Grade, type ItemSummary, type ItemView, type SearchFit } from "@/api/client";
import { Skeleton } from "@/components/ui/skeleton";
import { useShownLate } from "@/lib/useShownLate";
import { cn } from "@/lib/utils";
import { facetQuery, isFiltered, type FacetSelection } from "@/facet/FacetFilters";
import { rangeParams, type NumberRanges } from "@/number/NumberFilters";
import { ItemCard } from "./ItemCard";
import { workDialogOpen } from "./dialogStack";
import { ItemDialog } from "./ItemDialog";
import { ItemRow } from "./ItemRow";
import { ITEMS } from "./lists";
import { KEY_GRADES } from "./pieces";
import { useRateItem } from "./useRateItem";
import type { SortChoice } from "./sort";

const PAGE_SIZE = 48;

export type Layout = "grid" | "list";

/**
 * What the board tells of its list: how many works it holds, whether what is shown is still the list of
 * the conditions before — a new search, filter, source, range, order or view asked for, its list not come
 * ([pending], marked only after a moment) — and whether the search is among what changed ([searching]).
 */
export type ListState = { total?: number; pending: boolean; searching: boolean };

type Props = {
  type: ContentTypeInfo;
  view: ItemView;
  sort: SortChoice;
  search: string;
  facets: FacetSelection;
  /** The sources shown; all when empty. */
  sources: string[];
  /** The ranges of numbers the list is limited to. */
  ranges: NumberRanges;
  layout: Layout;
  onState: (state: ListState) => void;
};

/**
 * THE LIST OF THE WORKS, a page at a time. A change of what it lists keeps the list before till the new
 * one comes, MARKED AS THE OLD ONE while it waits — the usual way of a list read anew (React's stale
 * content, TanStack's placeholder data): dimmed, still to open and grade, a thin bar running over it,
 * busy to a screen reader; nothing marked for a list back in a moment ([useShownLate]). Drawn anew only when
 * what it is given changes: the screen around it is drawn every time the loads are read, every second while
 * they run, and a tablet drew the whole list each time.
 */
export const ItemBoard = memo(function ItemBoard({ type, view, sort, search, facets, sources, ranges, layout, onState }: Props) {
  const { t } = useTranslation("item");
  const [selected, setSelected] = useState(0);
  const [opened, setOpened] = useState<number | null>(null);
  const cards = useRef(new Map<string, HTMLElement>());
  const grid = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const rate = useRateItem();

  // Every facet's choice as kept — the shorter list at the choice: two fandoms checked are sent as two
  const facetChoices = useMemo(() => facetQuery(facets), [facets]);
  const list = useInfiniteQuery({
    queryKey: [ITEMS, type.id, view, sort, search, facetChoices, sources, ranges],
    initialPageParam: 0,
    // A list given up — another search, filter or order asked for — is cancelled, its request aborted: a
    // search is seconds of the reranker, and those left to run piled up behind the one waited for
    queryFn: async ({ pageParam, signal }) =>
      unwrap(
        await api.POST("/api/types/{type}/items", {
          signal,
          params: { path: { type: type.id } },
          body: {
            view,
            sort: sort.sort,
            sortNumber: sort.sort === "NUMBER" ? sort.number : undefined,
            search: search || undefined,
            facets: facetChoices.length ? facetChoices : undefined,
            sources: sources.length ? sources : undefined,
            ranges: Object.keys(ranges).length ? rangeParams(ranges) : undefined,
            offset: pageParam,
            limit: PAGE_SIZE,
          },
        }),
      ),
    // A new filter or order keeps the list it changes until the new one comes: a checkbox ticked
    // after another blanked the whole board each time
    placeholderData: keepPreviousData,
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((n, p) => n + p.items.length, 0);
      return loaded < last.total && last.items.length > 0 ? loaded : undefined;
    },
  });

  // An item may come twice: pages are asked for by offset, and between two pages the order can
  // change under them — a job adds works and re-scores them as it goes. Two items of one work
  // are two cards: a link hides neither
  const items: ItemSummary[] = useMemo(() => {
    const seen = new Set<string>();
    return (list.data?.pages.flatMap((p) => p.items) ?? []).filter((s) => {
      const key = refKey(s);
      return !seen.has(key) && !!seen.add(key);
    });
  }, [list.data]);
  const total = list.data?.pages[0]?.total;
  // The list shown is of the conditions before while the new one is asked for
  const waiting = list.isPlaceholderData;
  const stale = useShownLate(waiting);
  // The search the list shown was found by: the search is among what changed while it is another
  const shownSearch = useRef(search);
  useEffect(() => {
    if (!waiting) shownSearch.current = search;
    onState({ total, pending: stale, searching: stale && shownSearch.current !== search });
  }, [total, waiting, stale, search, onState]);

  const { hasNextPage, isFetchingNextPage, fetchNextPage } = list;
  const more = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  // Endless scrolling: the next page is asked for when the end of the list comes into view
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const observer = new IntersectionObserver((entries) => entries[0].isIntersecting && more(), { rootMargin: "800px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, [more]);

  // The dialog walks the list too: keep a page ahead of it
  useEffect(() => {
    const at = opened ?? selected;
    if (at >= items.length - 6) more();
  }, [opened, selected, items.length, more]);

  useEffect(() => {
    if (selected >= items.length && items.length > 0) setSelected(items.length - 1);
  }, [items.length, selected]);

  // Another list asked for: the selection back to its first work, no work opened
  // biome-ignore lint/correctness/useExhaustiveDependencies: a reset run on a change of the list's conditions alone, which it does not read
  useEffect(() => {
    setSelected(0);
    setOpened(null);
  }, [view, sort, search, facets, sources]);

  const onRate = useCallback((summary: ItemSummary, grade: Grade | null) => rate.mutateAsync({ summary, grade }).catch(() => undefined), [rate]);

  /** How many cards stand in a row of the grid, for the up and down arrows. */
  const columns = () => {
    if (layout === "list" || !grid.current) return 1;
    return getComputedStyle(grid.current).gridTemplateColumns.split(" ").length;
  };

  // Keys of the board; a work's dialog has its own while it is open — this board's, or one a link opened over it
  useEffect(() => {
    if (opened !== null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (workDialogOpen()) return;
      if ((e.target as HTMLElement).closest("input, textarea, select, [role=dialog], [role=menu]")) return;
      const move = (to: number) => {
        e.preventDefault();
        const next = Math.max(0, Math.min(items.length - 1, to));
        setSelected(next);
        const summary = items[next];
        if (summary) cards.current.get(refKey(summary))?.scrollIntoView({ block: "nearest", behavior: "smooth" });
      };
      switch (e.key) {
        case "ArrowRight":
        case "l":
          return move(selected + 1);
        case "ArrowLeft":
        case "h":
          return move(selected - 1);
        case "ArrowDown":
        case "j":
          return move(selected + columns());
        case "ArrowUp":
        case "k":
          return move(selected - columns());
        case "Enter":
        case " ":
          e.preventDefault();
          if (items[selected]) setOpened(selected);
          return;
        default:
          if (KEY_GRADES[e.key] && items[selected]) {
            e.preventDefault();
            onRate(items[selected], KEY_GRADES[e.key]);
          }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  /** The user's word on how far a work found by the search answers the line. */
  const judge = search
    ? async (summary: ItemSummary, fit: SearchFit | null) =>
        ensureOk(
          await api.PUT("/api/types/{type}/search/judgments", {
            params: { path: { type: type.id } },
            body: { query: search, source: summary.source, item: summary.item, fit: fit ?? undefined },
          }),
        )
    : undefined;

  if (list.isLoading) {
    return (
      <div className="grid grid-cols-[repeat(auto-fill,minmax(20rem,1fr))] gap-4">
        {Array.from({ length: 8 }, (_, i) => (
          // biome-ignore lint/suspicious/noArrayIndexKey: eight alike placeholders while the list loads, never reordered
          <Skeleton key={i} className="aspect-[4/3] rounded-xl" />
        ))}
      </div>
    );
  }
  if (list.isError) return <div className="py-20 text-center text-destructive">{t("ItemBoard.loadFailed", { message: list.error.message })}</div>;
  /** The old list's look while the new one is asked for: dimmed, still to press. */
  const dimmed = cn("transition-opacity duration-200", stale && "opacity-50");
  if (items.length === 0) {
    const allRated = view === "UNRATED" && !search && !isFiltered(facets) && sources.length === 0 && Object.keys(ranges).length === 0;
    return (
      <>
        <Busy shown={stale} />
        <div className={cn("flex flex-col items-center gap-3 py-24 text-muted-foreground", dimmed)} aria-busy={waiting}>
          <InboxIcon className="size-12 opacity-50" />
          <div className="text-lg">{allRated ? t("ItemBoard.allRated") : t("ItemBoard.nothingFound")}</div>
          {allRated && <div className="text-sm">{t("ItemBoard.allRatedHint")}</div>}
        </div>
      </>
    );
  }

  const View = layout === "grid" ? ItemCard : ItemRow;
  return (
    <>
      <Busy shown={stale} />
      <div
        ref={grid}
        aria-busy={waiting}
        className={cn(layout === "grid" ? "grid grid-cols-[repeat(auto-fill,minmax(20rem,1fr))] gap-4" : "flex flex-col gap-2", dimmed)}
      >
        {items.map((summary, i) => {
          const key = refKey(summary);
          return (
            <View
              key={key}
              ref={(el: HTMLElement | null) => {
                if (el) cards.current.set(key, el);
                else cards.current.delete(key);
              }}
              type={type}
              summary={summary}
              selected={i === selected}
              onSelect={() => setSelected(i)}
              onOpen={() => {
                setSelected(i);
                setOpened(i);
              }}
              onRate={(grade) => onRate(summary, grade)}
              onJudge={judge && ((fit) => judge(summary, fit))}
            />
          );
        })}
      </div>
      <div ref={sentinel} className="flex justify-center py-8 text-muted-foreground">
        {isFetchingNextPage && <Loader2Icon className="size-6 animate-spin" />}
      </div>
      <ItemDialog
        items={items}
        index={opened}
        total={total ?? items.length}
        onIndex={(i) => {
          setOpened(i);
          if (i !== null) setSelected(i);
        }}
        onRate={onRate}
        ratedLeaves={view === "UNRATED"}
        onJudge={judge}
      />
    </>
  );
});

/**
 * The thin bar running over the list while its new list is asked for: under the sticky header, so it is
 * seen wherever the list is scrolled; still, of the width of the list, when motion is to be reduced. It
 * takes no room: the list does not move as it comes and goes.
 */
function Busy({ shown }: { shown: boolean }) {
  return (
    <div className="sticky top-14 z-30 -mb-0.5 h-0.5 overflow-hidden" aria-hidden>
      {shown && <div className="h-full w-1/3 animate-[list-busy_1.1s_ease-in-out_infinite] bg-primary motion-reduce:w-full motion-reduce:animate-none motion-reduce:opacity-60" />}
    </div>
  );
}
