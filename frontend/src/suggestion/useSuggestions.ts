import { useQuery } from "@tanstack/react-query";
import { api, unwrap, type FacetSuggestions, type ItemRef, type SourceInfo } from "@/api/client";

export function suggestionsKey(ref: ItemRef) {
  return ["suggestions", ref.source, ref.item];
}

/**
 * The values the application suggests for the item's facets, and the site's values it doubts;
 * asked for only when the source has a facet to suggest values of.
 */
export function useSuggestions(ref: ItemRef, source: SourceInfo | undefined) {
  return useQuery({
    queryKey: suggestionsKey(ref),
    enabled: source?.facets.some((f) => f.suggest) ?? false,
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/suggestions", { params: { path: { source: ref.source, item: ref.item } } })),
  });
}

/** The suggestions of one facet, or none. */
export function suggestionsOf(all: FacetSuggestions[] | undefined, facet: string): FacetSuggestions | undefined {
  return all?.find((s) => s.facet === facet);
}
