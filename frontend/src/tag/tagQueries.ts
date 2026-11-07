import { useQueryClient } from "@tanstack/react-query";

/** The cache key of everything of the tags, of every type: ["tags", …]. */
export const TAGS = "tags";

/** The pause in the typing after which a list of the tags is asked again. */
export const QUERY_SETTLE_MS = 400;

/** What a change of the tags is read anew by: the tags, the works, the filters. */
export function useTagsChanged() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: [TAGS] }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      queryClient.invalidateQueries({ queryKey: ["facets"] }),
    ]);
}
