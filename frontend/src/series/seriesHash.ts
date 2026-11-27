/**
 * The series a link opens: `#series=<type>/<id>` — over whatever is open, a work's screen too — by the
 * number of the normalized series.
 */
const PREFIX = "#series=";

export interface SeriesRef {
  type: string;
  id: number;
}

/** Whether the address names a series. */
export function isSeriesHash(hash: string = window.location.hash): boolean {
  return hash.startsWith(PREFIX);
}

export function readSeriesHash(): SeriesRef | null {
  const hash = window.location.hash;
  if (!isSeriesHash(hash)) return null;
  const rest = hash.slice(PREFIX.length);
  const slash = rest.indexOf("/");
  if (slash <= 0 || slash === rest.length - 1) return null;
  const id = Number(rest.slice(slash + 1));
  if (!Number.isSafeInteger(id)) return null;
  try {
    return { type: decodeURIComponent(rest.slice(0, slash)), id };
  } catch {
    // A broken escape in a hand-made link: nothing to open
    return null;
  }
}

/** The address that opens the series [id] of the [type] over whatever is open. */
export function seriesHref(type: string, id: number): string {
  return `${PREFIX}${encodeURIComponent(type)}/${id}`;
}

/** The key of the queries of the series of a type. */
export function seriesKey(type: string): readonly unknown[] {
  return ["series", type];
}

/** The key of the queries of the windows of the series of a type, each one series: read again without the list. */
export function seriesWindowsKey(type: string): readonly unknown[] {
  return [...seriesKey(type), "one"];
}

/** The key of the queries of the list of the series of a type. */
export function seriesListKey(type: string): readonly unknown[] {
  return [...seriesKey(type), "list"];
}
