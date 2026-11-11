import { useQuery, useQueryClient } from "@tanstack/react-query";
import { EqualIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type ItemRef } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { PairFacts, PairGrid } from "@/duplicate/PairCompare";
import { seriesKey, seriesWindowsKey } from "./seriesHash";

/**
 * TWO WORKS OF A SERIES MADE ONE, asked first: the two compared as the screen of duplicates shows a pair
 * ([PairFacts], [PairGrid]) — how alike they are, as the last search kept them or worked out now —, and "one work" or
 * nothing. One work, their items one work across the sites, as the word "one work" on a pair makes them.
 */
export function MergeWorks({ type, first, second, onClose }: { type: ContentTypeInfo; first: ItemRef; second: ItemRef; onClose: () => void }) {
  const { t } = useTranslation("series");
  const queryClient = useQueryClient();
  const pair = useQuery({
    queryKey: [...seriesKey(type.id), "pair", first.source, first.item, second.source, second.item],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/types/{type}/duplicates/pair", {
          params: { path: { type: type.id }, query: { source: first.source, item: first.item, otherSource: second.source, otherItem: second.item } },
        }),
      ),
  });
  const merge = async () => {
    ensureOk(await api.PUT("/api/types/{type}/duplicates/answer", { params: { path: { type: type.id } }, body: { first, second, verdict: "SAME" } }));
    // The window of the series read again, the list under it not: its rows stay in their places ([SeriesDialog])
    await queryClient.invalidateQueries({ queryKey: seriesWindowsKey(type.id) });
    await queryClient.invalidateQueries({ queryKey: ["item"] });
    onClose();
  };
  const p = pair.data;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{t("Merge.title")}</DialogTitle>
          <DialogDescription>{t("Merge.description")}</DialogDescription>
        </DialogHeader>
        {pair.isLoading && <p className="text-sm text-muted-foreground">{t("SeriesScreen.loading")}</p>}
        {pair.isError && <p className="text-sm text-destructive">{t("SeriesScreen.failed", { error: pair.error.message })}</p>}
        {p && (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <PairFacts pair={p} />
            </div>
            <PairGrid pair={p} />
            <div className="flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={onClose}>
                {t("Merge.cancel")}
              </Button>
              <AsyncButton size="sm" icon={<EqualIcon />} title={t("Merge.sameHint")} onClick={merge}>
                {t("Merge.same")}
              </AsyncButton>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
