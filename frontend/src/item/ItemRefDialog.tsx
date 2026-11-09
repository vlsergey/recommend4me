import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { Grade, ItemRef, ItemSummary } from "@/api/client";
import { toast } from "@/components/ui/toast";
import { ItemDialog } from "./ItemDialog";
import { writeTopHash } from "./dialogStack";
import { isSeriesHash } from "@/series/seriesHash";
import { readItemHash } from "./itemHash";
import { itemKey } from "./lists";
import { useRateItem } from "./useRateItem";
import { readWork, whilePending } from "./workRead";

/**
 * The dialog of one item known only by its source and id — from outside the list: a page the
 * browser extension sent, a link of a series. Its card is read from the item's details, its screen
 * open at once and outlined meanwhile ([WorkOpening]).
 */
export function ItemRefDialog({ item, onClose }: { item: ItemRef | null; onClose: () => void }) {
  const { t } = useTranslation("item");
  const queryClient = useQueryClient();
  const details = useQuery({
    queryKey: item ? itemKey(item) : ["item", "none"],
    enabled: item !== null,
    // Run only with an item ([enabled]): no item reads none
    queryFn: async () => (item ? readWork(queryClient, item) : null),
    refetchInterval: whilePending,
  });
  const rate = useRateItem();
  const onRate = useCallback((summary: ItemSummary, grade: Grade | null) => rate.mutateAsync({ summary, grade }).catch(() => undefined), [rate]);
  const summary = item && details.data ? details.data.summary : undefined;

  // An item the application does not know opens nothing: say so instead of staying silent
  const failed = item !== null && details.isError ? details.error.message : null;
  useEffect(() => {
    if (failed === null) return;
    toast.add({ title: t("ItemRefDialog.failed"), description: failed, type: "error" });
    onClose();
  }, [failed, onClose, t]);

  return (
    <ItemDialog
      items={summary ? [summary] : []}
      index={summary ? 0 : null}
      total={1}
      onIndex={(i) => i === null && onClose()}
      onRate={onRate}
      ratedLeaves={false}
      // Opened at the press, the work read meanwhile: never a press that shows nothing
      opening={item !== null && !details.data && !details.isError}
    />
  );
}

/**
 * The dialog of the item the address names, `#item=<source>/<item>`: the browser extension links
 * to it. A link followed while the application is open opens its item too.
 */
export function HashItemDialog() {
  const [item, setItem] = useState(readItemHash);
  useEffect(() => {
    // A series opened from a work leaves the work open under it
    const onHash = () => {
      if (!isSeriesHash()) setItem(readItemHash());
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  // Closed — or never opened, of an item the application does not know: the address names the
  // dialog left on top, or none
  const close = useCallback(() => {
    setItem(null);
    writeTopHash();
  }, []);
  return <ItemRefDialog item={item} onClose={close} />;
}
