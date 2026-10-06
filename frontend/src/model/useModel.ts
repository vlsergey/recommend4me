import { useQuery } from "@tanstack/react-query";
import { api, unwrap, type Grade } from "@/api/client";

export function useModel(type: string) {
  return useQuery({
    queryKey: ["model", type],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/model", { params: { path: { type } } })),
    // Training runs in the background a few seconds after a grade; follow it until it is done
    refetchInterval: (q) => (q.state.data?.training ? 1500 : false),
  });
}

/** Where every grade fell on the user's 0..10, as the trained model has it; empty before training. */
export function useLadder(type: string): Partial<Record<Grade, number>> {
  const ladder = useModel(type).data?.ladder ?? [];
  return Object.fromEntries(ladder.map((l) => [l.grade, l.score]));
}
