import { useQuery, type QueryClient } from "@tanstack/react-query";
import { api, refKey, unwrap, type ItemRef, type SourceInfo } from "@/api/client";
import { applyDetails } from "@/item/lists";

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

/** The candidates of one facet of the item — every one, or the likeliest [limit]; the prefix of them all is the item's, of a facet the facet's. */
export function candidatesKey(ref: ItemRef, facet?: string, limit?: number) {
  return facet === undefined ? ["candidates", ref.source, ref.item] : ["candidates", ref.source, ref.item, facet, limit ?? "all"];
}

/** How long candidates just read stay current: an answer reads them anew all the same (invalidated). */
const JUST_READ = 5_000;

/** The query of the candidates of a facet of the work: every one, or the likeliest [limit] alone. */
export function candidatesQuery(ref: ItemRef, facet: string, limit?: number) {
  return {
    queryKey: candidatesKey(ref, facet, limit),
    // Read by the press of "…" just before the line shows them: not read again at once as it does
    staleTime: limit === undefined ? 0 : JUST_READ,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/items/{source}/{item}/candidates", { params: { path: { source: ref.source, item: ref.item }, query: { facet, limit } } }),
      ),
  };
}

/**
 * Every value of a facet the work may be given and lacks — the characters of its universes, the
 * pairs of its characters — with the model's chance and how often its texts name it, the likeliest
 * first, or the likeliest [limit] alone: of the tags, every one is thousands; asked for only when [enabled].
 */
export function useCandidates(ref: ItemRef, facet: string, enabled: boolean, limit?: number) {
  // So many more asked for — a value of the line answered on, the line longer — the ones shown stay
  // till the next come; never those of another work or facet, whose chips would answer on this one
  const own = candidatesKey(ref, facet).slice(0, -1);
  return useQuery({
    ...candidatesQuery(ref, facet, limit),
    enabled,
    placeholderData: (previous, query) =>
      limit !== undefined && query && own.every((part, i) => query.queryKey[i] === part) ? previous : undefined,
  });
}

/** How many times each work has waited for the chances made after answers: a wait begun before the last answer is stale. */
const settling = new Map<string, number>();

/**
 * The work, its suggestions and its candidates read again once the application has made the
 * chances an answer changed — made apart from the answer, a second or two. An answer given
 * meanwhile makes this wait stale: the one after it waits for the chances of all of them.
 */
export function suggestionsLater(queryClient: QueryClient, ref: ItemRef) {
  const key = refKey(ref);
  const asked = (settling.get(key) ?? 0) + 1;
  settling.set(key, asked);
  void api.GET("/api/items/{source}/{item}/suggestions/settled", { params: { path: { source: ref.source, item: ref.item } } }).then((result) => {
    if (settling.get(key) !== asked || !result.response.ok) return;
    settling.delete(key);
    // The work as the application now has it, in the dialog and in the lists alike
    void api.GET("/api/items/{source}/{item}", { params: { path: { source: ref.source, item: ref.item } } }).then((read) => {
      if (read.data !== undefined && settling.get(key) === undefined) applyDetails(queryClient, read.data, ref);
    });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(ref) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(ref) });
  });
}
