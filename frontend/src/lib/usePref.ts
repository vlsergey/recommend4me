import { useCallback, useState } from "react";

/**
 * A small display preference that survives a reload (theme, view mode). Reads and writes are
 * wrapped in try/catch: in a private window access to localStorage itself throws, and the
 * preference then lives until the reload instead of breaking the page.
 */
export function usePref<T>(key: string, fallback: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? fallback : (JSON.parse(raw) as T);
    } catch {
      return fallback;
    }
  });

  const put = useCallback(
    (v: T) => {
      setValue(v);
      try {
        window.localStorage.setItem(key, JSON.stringify(v));
      } catch {
        // Nowhere to keep it: the preference lives until the reload
      }
    },
    [key],
  );

  return [value, put];
}
