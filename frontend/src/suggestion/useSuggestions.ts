import { useQuery } from "@tanstack/react-query";
import { api, unwrap, type ItemRef, type SourceInfo } from "@/api/client";

export function suggestionsKey(ref: ItemRef) {
  return ["suggestions", ref.source, ref.item];
}

/**
 * The values the application suggests for the item's facets — ones the work lacks and more likely
 * has than not; asked for only when the source has a facet to suggest values of.
 */
export function useSuggestions(ref: ItemRef, source: SourceInfo | undefined) {
  return useQuery({
    queryKey: suggestionsKey(ref),
    enabled: source?.facets.some((f) => f.suggest) ?? false,
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/suggestions", { params: { path: { source: ref.source, item: ref.item } } })),
  });
}

/** Every candidate of one facet of the item; the prefix of them all is the item's. */
export function candidatesKey(ref: ItemRef, facet?: string) {
  return facet === undefined ? ["candidates", ref.source, ref.item] : ["candidates", ref.source, ref.item, facet];
}

/**
 * Every value of a facet the work may be given and lacks — the characters of its universes, the
 * pairs of its characters — with the model's chance and how often its texts name it, the likeliest
 * first; asked for only when [enabled].
 */
export function useCandidates(ref: ItemRef, facet: string, enabled: boolean) {
  return useQuery({
    queryKey: candidatesKey(ref, facet),
    enabled,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/items/{source}/{item}/candidates", { params: { path: { source: ref.source, item: ref.item }, query: { facet } } }),
      ),
  });
}
