import { useEffect, useRef, useState } from "react";

/** How long a wait goes unmarked: a list back in a moment draws nothing. */
const DELAY_MS = 300;

/** How long a mark once shown stays at least: it does not flash for a list back just after it showed. */
const MINIMUM_MS = 400;

/**
 * [on] as it is shown: only once it has held for [delay] ms, and then for [minimum] ms at least —
 * the usual guard of a busy mark against flashing (a wait of a moment marks nothing; a mark shown does
 * not blink off at once).
 */
export function useShownLate(on: boolean, delay = DELAY_MS, minimum = MINIMUM_MS): boolean {
  const [shown, setShown] = useState(false);
  const since = useRef(0);
  useEffect(() => {
    if (on === shown) return;
    const wait = on ? delay : Math.max(0, minimum - (Date.now() - since.current));
    const timer = setTimeout(() => {
      if (on) since.current = Date.now();
      setShown(on);
    }, wait);
    return () => clearTimeout(timer);
  }, [on, shown, delay, minimum]);
  return shown;
}
