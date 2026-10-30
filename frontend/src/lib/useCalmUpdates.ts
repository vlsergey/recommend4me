import { useEffect, useLayoutEffect, useRef, useState } from "react";

/**
 * How long the rows move to their places once the list is read anew, and how long the list stays
 * locked after that: as the table of the similar tags (tag/TagsScreen.tsx `Similar`).
 */
const MOVE_MS = 1000;
const SETTLE_MS = 1000;

/** A row answered on: how long it fades where it stands, and how long the rows after it move up into its place. */
const LEAVE_MS = 500;
const CLOSE_MS = 500;

/**
 * A LIST READ AGAIN SHOWN CALMLY, as the table of the similar tags is: the list read ([incoming])
 * is held against the one shown; the same, nothing moves; the same rows in their places, one of them
 * not [same], it is shown at once, nothing locked; another — a row more, a row less, another
 * order — the list is [locked] (taking no press, shown busy), every row
 * moves for [MOVE_MS] from where it stood to where it stands now, a new one fading in, and it takes
 * presses again [SETTLE_MS] after: a row that moved is never pressed by the click meant for the one
 * that was there.
 *
 * A row answered on leaves at once, as the user sees it kept ([remove]): it fades for [LEAVE_MS],
 * the rows after it move up into its place for [CLOSE_MS], the list locked meanwhile. A list read
 * while the rows move waits till they stand.
 *
 * Every row is given [rowRef] of its key: where it stands is what it moves from.
 */
export function useCalmUpdates<T>(incoming: T[] | undefined, keyOf: (row: T) => string, same: (a: T, b: T) => boolean) {
  const [shown, setShown] = useState<T[] | undefined>(incoming);
  const [locked, setLocked] = useState(false);
  const shownNow = useRef(shown);
  shownNow.current = shown;
  const lockedNow = useRef(false);
  const rows = useRef(new Map<string, HTMLElement>());
  /** Where every row stood before the list changed, and how long it moves to its new place: read once the new list is drawn. */
  const before = useRef<{ tops: Map<string, number>; ms: number } | null>(null);
  /** A list read while the rows moved: shown once they stand. */
  const waiting = useRef<T[] | undefined>(undefined);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const later = (ms: number, then: () => void) => timers.current.push(setTimeout(then, ms));
  const lock = (on: boolean) => {
    lockedNow.current = on;
    setLocked(on);
  };
  const tops = () => new Map([...rows.current].map(([k, e]) => [k, e.getBoundingClientRect().top]));

  /** The rows standing: a list read meanwhile shown now, else the list free. */
  const release = () => {
    const next = waiting.current;
    waiting.current = undefined;
    lock(false);
    if (next) take(next);
  };

  /** A list read: the same, nothing; another, the rows moved to it, the list locked till they stand and a while after. */
  const take = (next: T[]) => {
    if (lockedNow.current) {
      waiting.current = next;
      return;
    }
    const old = shownNow.current;
    if (!old) {
      setShown(next);
      return;
    }
    const inPlace = old.length === next.length && old.every((o, i) => keyOf(o) === keyOf(next[i]));
    if (inPlace && old.every((o, i) => same(o, next[i]))) return;
    // The same rows in the same places, a word of one of them changed: nothing moves, no press goes astray — shown at once
    if (inPlace) {
      setShown(next);
      return;
    }
    lock(true);
    before.current = { tops: tops(), ms: MOVE_MS };
    setShown(next);
    later(MOVE_MS + SETTLE_MS, release);
  };

  useEffect(() => {
    if (incoming) take(incoming);
    // The list read alone: the key and the likeness are the caller's way of comparing, not a reason to compare again
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [incoming]);

  /** The rows [keys] answered on: faded where they stand, the rows after them moved up, the list locked meanwhile. */
  const remove = (keys: string[]) => {
    const gone = new Set(keys);
    lock(true);
    gone.forEach((k) => {
      const e = rows.current.get(k);
      if (e) {
        e.style.transition = `opacity ${LEAVE_MS}ms ease`;
        e.style.opacity = "0";
      }
    });
    later(LEAVE_MS, () => {
      before.current = { tops: tops(), ms: CLOSE_MS };
      setShown((list) => list?.filter((row) => !gone.has(keyOf(row))));
      later(CLOSE_MS, release);
    });
  };

  // The rows of the list changed: each moved from where it stood to where it stands, a new one faded in
  useLayoutEffect(() => {
    const was = before.current;
    if (!was) return;
    before.current = null;
    rows.current.forEach((e, k) => {
      const top = was.tops.get(k);
      e.style.transition = "none";
      if (top === undefined) e.style.opacity = "0";
      else e.style.transform = `translateY(${top - e.getBoundingClientRect().top}px)`;
    });
    requestAnimationFrame(() =>
      rows.current.forEach((e) => {
        e.style.transition = `transform ${was.ms}ms ease, opacity ${was.ms}ms ease`;
        e.style.transform = "";
        e.style.opacity = "";
      }),
    );
  }, [shown]);

  const rowRef = (key: string) => (e: HTMLElement | null) => {
    if (e) rows.current.set(key, e);
    else rows.current.delete(key);
  };
  return { shown, locked, remove, rowRef };
}
