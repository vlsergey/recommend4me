/**
 * The series a link opens: `#series=<type>/<key>` — over whatever is open, a work's screen too. The
 * key may hold a slash of its own ("ficbook/28510"): only the first one ends the type.
 */
const PREFIX = "#series=";

export interface SeriesRef {
  type: string;
  key: string;
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
  try {
    return { type: decodeURIComponent(rest.slice(0, slash)), key: decodeURIComponent(rest.slice(slash + 1)) };
  } catch {
    // A broken escape in a hand-made link: nothing to open
    return null;
  }
}

/** The address that opens the series [key] of the [type] over whatever is open. */
export function seriesHref(type: string, key: string): string {
  return `${PREFIX}${encodeURIComponent(type)}/${encodeURIComponent(key)}`;
}

/** The key of the queries of the series of a type. */
export function seriesKey(type: string): readonly unknown[] {
  return ["series", type];
}
