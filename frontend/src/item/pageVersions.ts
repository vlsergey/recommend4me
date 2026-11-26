import { api, failure, type ItemPageVersion, type ItemQuery } from "@/api/client";

/**
 * THE VERSIONS OF A PAGE OF A SEARCH as the server makes them (listItemVersions): a line of JSON each, the
 * works found by words and by meaning first, then as the reranker reads them a portion at a time, then with
 * their texts read by their pieces. Every line is given to [onVersion] as it comes; the last read is returned
 * — the page itself when it has no `refining`, else the stream broke off before its end. Aborted by [signal],
 * the server's search ends at its next line.
 */
export async function readVersions(
  type: string,
  body: ItemQuery,
  signal: AbortSignal,
  onVersion: (version: ItemPageVersion) => void,
): Promise<ItemPageVersion | undefined> {
  const result = await api.POST("/api/types/{type}/items/versions", { signal, params: { path: { type } }, body, parseAs: "stream" });
  if (!result.response.ok || !result.data) throw failure(result);
  const reader = result.data.pipeThrough(new TextDecoderStream()).getReader();
  let rest = "";
  let last: ItemPageVersion | undefined;
  const take = (line: string) => {
    if (line.trim() === "") return;
    last = JSON.parse(line) as ItemPageVersion;
    onVersion(last);
  };
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    rest += value;
    for (let at = rest.indexOf("\n"); at >= 0; at = rest.indexOf("\n")) {
      take(rest.slice(0, at));
      rest = rest.slice(at + 1);
    }
  }
  take(rest);
  return last;
}
