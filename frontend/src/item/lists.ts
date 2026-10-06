import type { InfiniteData, QueryClient, QueryKey } from "@tanstack/react-query";
import { sameItem, type ItemDetails, type ItemPage, type ItemRef, type ItemSummary } from "@/api/client";

/** The cache key of a page list: ["items", type, view, …the rest of the query]. */
export const ITEMS = "items";

export function itemKey(ref: ItemRef): QueryKey {
  return ["item", ref.source, ref.item];
}

type Lists = InfiniteData<ItemPage>;

function eachList(queryClient: QueryClient, change: (data: Lists, key: QueryKey) => Lists) {
  for (const [key] of queryClient.getQueriesData<Lists>({ queryKey: [ITEMS] })) {
    queryClient.setQueryData<Lists>(key, (data) => (data ? change(data, key) : data));
  }
}

/** The card of an item is replaced in place in every loaded list: its order is not touched. */
export function replaceInLists(queryClient: QueryClient, updated: ItemSummary) {
  eachList(queryClient, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({ ...page, items: page.items.map((s) => (sameItem(s, updated) ? updated : s)) })),
  }));
}

/** The card leaves every loaded list: it became a part of another card. */
export function removeFromLists(queryClient: QueryClient, ref: ItemRef) {
  eachList(queryClient, (data) => ({
    ...data,
    pages: data.pages.map((page) => {
      const had = page.items.some((s) => sameItem(s, ref));
      return { total: had ? page.total - 1 : page.total, items: page.items.filter((s) => !sameItem(s, ref)) };
    }),
  }));
}

/**
 * A grade changed: a card graded in the "unrated" view leaves it at once, so the next one moves
 * up under the cursor; one ungraded in the "rated" view leaves that. Elsewhere it is replaced.
 */
export function applyGrade(queryClient: QueryClient, updated: ItemSummary) {
  eachList(queryClient, (data, key) => {
    const view = key[2];
    const leaves = (view === "UNRATED" && updated.grade) || (view === "RATED" && !updated.grade);
    return {
      ...data,
      pages: data.pages.map((page) => {
        const had = page.items.some((s) => sameItem(s, updated));
        return {
          total: leaves && had ? page.total - 1 : page.total,
          items: leaves ? page.items.filter((s) => !sameItem(s, updated)) : page.items.map((s) => (sameItem(s, updated) ? updated : s)),
        };
      }),
    };
  });
}

/** New details of an item from a correction or a download: the dialog and the lists get them at once. */
export function applyDetails(queryClient: QueryClient, details: ItemDetails, asked: ItemRef) {
  queryClient.setQueryData(itemKey(asked), details);
  if (!sameItem(asked, details.summary)) queryClient.setQueryData(itemKey(details.summary), details);
  replaceInLists(queryClient, details.summary);
}
