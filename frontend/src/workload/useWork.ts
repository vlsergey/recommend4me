import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/api/client";

/** A work of the queue by its name, as the queue names it: what it does, and what it is of. */
export type WorkName = { what: string; type?: string; source?: string; facet?: string; item?: string };

/** How often the state of a work not done is asked for again: a few bytes. */
const EVERY_MS = 3000;

/**
 * A WORK OF THE QUEUE A SCREEN WAITS ON, as it stands ([api] getWork): read again while it is to come —
 * planned, waiting, running — and once more after the user asked for it ([asked]) till a run newer
 * than the last done before the asking has ended; [onEnd] once it has, the screen reading what it made
 * then, not while it runs. [busy]: a run of it is to come.
 */
export function useWork(work: WorkName, onEnd: () => void) {
  // The end of the last run done when the user asked for a new one: till another ends, the run asked for is to come
  // (between planned and waiting the work may stand nowhere for a moment); null — nothing asked
  const [awaited, setAwaited] = useState<string | undefined | null>(null);
  const state = useQuery({
    queryKey: ["work", work.what, work.type, work.source, work.facet, work.item],
    queryFn: async () => {
      const result = await api.GET("/api/workload/work", { params: { query: work } });
      if (result.response.status === 404) return null;
      if (!result.data) throw new Error(result.response.statusText || String(result.response.status));
      return result.data;
    },
    refetchInterval: (query) => {
      const w = query.state.data;
      return (w && w.state !== "FINISHED") || (awaited !== null && w?.ended === awaited) ? EVERY_MS : false;
    },
  });
  const w = state.data;
  const busy = (w != null && w.state !== "FINISHED") || (awaited !== null && w?.ended === awaited);
  const wasBusy = useRef(busy);
  // biome-ignore lint/correctness/useExhaustiveDependencies: the end of a run alone; the caller's [onEnd] is made anew at every drawing
  useEffect(() => {
    if (wasBusy.current && !busy) {
      setAwaited(null);
      onEnd();
    }
    wasBusy.current = busy;
  }, [busy]);
  /** The user asked for a run: waited on till one newer than the last done ends. */
  const asked = () => {
    setAwaited(w?.ended);
    void state.refetch();
  };
  return { busy, asked };
}
