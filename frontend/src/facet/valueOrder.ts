import { useRef } from "react";
import type { FacetValueInfo } from "@/api/client";

/** The user has answered on the value: it is the work's by the user's word, or taken away by it. */
export const decided = (v: FacetValueInfo) => v.corrected !== undefined && v.corrected !== null;

/** The first order of a facet's values: what the user decided first, then the rest the likeliest first. */
function firstOrder(values: FacetValueInfo[]): string[] {
  return [...values]
    .sort((a, b) => Number(decided(b)) - Number(decided(a)) || (b.chance ?? 0) - (a.chance ?? 0))
    .map((v) => v.key);
}

/**
 * The values in the order they were first shown in: an answer changes how a value looks, never
 * where it is — the next value stays under the cursor. A value that comes later goes after them.
 */
export function useStableOrder(values: FacetValueInfo[]): FacetValueInfo[] {
  const order = useRef<string[]>([]);
  const known = new Set(order.current);
  const fresh = firstOrder(values.filter((v) => !known.has(v.key)));
  if (fresh.length > 0) order.current = [...order.current, ...fresh];
  const at = new Map(order.current.map((k, i) => [k, i]));
  return [...values].sort((a, b) => (at.get(a.key) ?? 0) - (at.get(b.key) ?? 0));
}
