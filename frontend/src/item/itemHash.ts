import type { ItemRef } from "@/api/client";

/**
 * The item a link opens: `#item=<source>/<item>` — the browser extension links to it. The item's
 * id may hold slashes of its own: only the first one ends the source.
 */
const PREFIX = "#item=";

export function readItemHash(): ItemRef | null {
  const hash = window.location.hash;
  if (!hash.startsWith(PREFIX)) return null;
  const rest = hash.slice(PREFIX.length);
  const slash = rest.indexOf("/");
  if (slash <= 0 || slash === rest.length - 1) return null;
  try {
    return { source: decodeURIComponent(rest.slice(0, slash)), item: decodeURIComponent(rest.slice(slash + 1)) };
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
  return `${PREFIX}${encodeURIComponent(ref.source)}/${ref.item.split("/").map(encodeURIComponent).join("/")}`;
}
