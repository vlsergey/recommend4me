import type { QueryClient } from "@tanstack/react-query";
import { api, refKey, unwrap, type ItemDetails, type ItemRef } from "@/api/client";
import { itemKey } from "./lists";

/** How many answers on each work, by "source/item", the application has kept: a read of the work begun before one is stale. */
const kept = new Map<string, number>();

/** How many answers on the work were kept: a read begun at one count and ended at another is stale. */
export const keptOn = (item: ItemRef) => kept.get(refKey(item)) ?? 0;

/** An answer on the work kept, shown by the page: a read of the work begun before it would show the work without it. */
export function answerKept(item: ItemRef) {
  kept.set(refKey(item), keptOn(item) + 1);
}

/**
 * The work as the application has it, read so that the page never shows it older than it already
 * does: an answer kept while it was read — the page showing it already, a read of seconds — makes
 * the read stale, and the work shown is kept, read anew after the answers' quiet all the same
 * (useCorrections settleLater); of a work the page shows nothing of yet, read again.
 */
export async function readWork(queryClient: QueryClient, item: ItemRef): Promise<ItemDetails> {
  for (;;) {
    const before = keptOn(item);
    const read = unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: { source: item.source, item: item.item } } }));
    if (keptOn(item) === before) return read;
    const shown = queryClient.getQueryData<ItemDetails>(itemKey(item));
    if (shown) return shown;
  }
}

/** The query of the work's details, read by [readWork]. */
export function workQuery(queryClient: QueryClient, item: ItemRef) {
  return { queryKey: itemKey(item), queryFn: () => readWork(queryClient, item) };
}
