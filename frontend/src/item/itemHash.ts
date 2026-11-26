import type { ItemRef } from "@/api/client";

/**
 * The item a link opens: `#item=<source>/<item>`, the item by the application's number of it — the
 * browser extension links to it.
 */
const PREFIX = "#item=";

export function readItemHash(): ItemRef | null {
  const hash = window.location.hash;
  if (!hash.startsWith(PREFIX)) return null;
  const rest = hash.slice(PREFIX.length);
  const slash = rest.indexOf("/");
  if (slash <= 0 || slash === rest.length - 1) return null;
  // The application's number of the item: a link of a site's id of before opens nothing
  const item = Number(rest.slice(slash + 1));
  if (!Number.isSafeInteger(item)) return null;
  try {
    return { source: decodeURIComponent(rest.slice(0, slash)), item };
  } catch {
    // A broken escape in a hand-made link: nothing to open
    return null;
  }
}

/**
 * Puts the open item into the address, or takes it out ([ref] null). The entry of the history is
 * replaced, not added, and no `hashchange` comes of it: the address only follows the dialog.
 */
export function writeItemHash(ref: ItemRef | null) {
  const hash = ref ? itemHref(ref) : "";
  if (hash === window.location.hash || (!ref && !window.location.hash.startsWith(PREFIX))) return;
  window.history.replaceState(window.history.state, "", `${window.location.pathname}${window.location.search}${hash}`);
}

/** The address that opens an item over whatever is open: a link to another work from a work's dialog. */
export function itemHref(ref: ItemRef): string {
  return `${PREFIX}${encodeURIComponent(ref.source)}/${ref.item}`;
}
