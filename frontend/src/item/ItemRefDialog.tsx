import { useCallback } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap, type Grade, type ItemRef, type ItemSummary } from "@/api/client";
import { ItemDialog } from "./ItemDialog";
import { itemKey } from "./lists";
import { useRateItem } from "./useRateItem";

/**
 * The dialog of one item known only by its source and id — from outside the list: a page the
 * browser extension sent. Its card is read from the item's details.
 */
export function ItemRefDialog({ item, onClose }: { item: ItemRef | null; onClose: () => void }) {
  const details = useQuery({
    queryKey: item ? itemKey(item) : ["item", "none"],
    enabled: item !== null,
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: { source: item!.source, item: item!.item } } })),
  });
  const rate = useRateItem();
  const onRate = useCallback((summary: ItemSummary, grade: Grade | null) => rate.mutate({ summary, grade }), [rate]);
  const summary = item && details.data ? details.data.summary : undefined;
  return (
    <ItemDialog
      items={summary ? [summary] : []}
      index={summary ? 0 : null}
      total={1}
      onIndex={(i) => i === null && onClose()}
      onRate={onRate}
      ratedLeaves={false}
    />
  );
}
