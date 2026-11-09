import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLinkIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type ItemRef, type SeriesSite } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useTypes } from "@/contenttype/types";
import { formatDay } from "@/i18n/format";
import { label, sourceShort, sourceTitle } from "@/i18n/label";
import { writeTopHash } from "@/item/dialogStack";
import { readSeriesHash, type SeriesRef, seriesKey } from "./seriesHash";
import { MergeWorks } from "./MergeWorks";
import { SeriesWorks } from "./SeriesWorks";

/**
 * THE WINDOW OF A NORMALIZED SERIES, over whatever is open: what every site says of it — its title
 * with the page, its status, its dates, its description — and its works in order, each with its
 * numbers on the sites; a work the user put in it taken out by its button, a work dragged onto
 * another of no site in common made one work with it, asked first ([MergeWorks]).
 */
export function SeriesDialog({ type, seriesRef, onClose }: { type: ContentTypeInfo; seriesRef: SeriesRef; onClose: () => void }) {
  const { t } = useTranslation("series");
  const queryClient = useQueryClient();
  const series = useQuery({
    queryKey: [...seriesKey(type.id), "one", seriesRef.key],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/series/one", { params: { path: { type: type.id }, query: { key: seriesRef.key } } })),
  });
  const s = series.data;
  // Two works dropped one on the other: made one once the user says so
  const [merging, setMerging] = useState<{ first: ItemRef; second: ItemRef } | null>(null);
  const takeOut = async (source: string, item: string) => {
    ensureOk(await api.DELETE("/api/types/{type}/series/works", { params: { path: { type: type.id }, query: { source, item } } }));
    await queryClient.invalidateQueries({ queryKey: seriesKey(type.id) });
    await queryClient.invalidateQueries({ queryKey: ["item"] });
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{s ? s.title : series.isError ? t("Series.failed") : t("SeriesScreen.loading")}</DialogTitle>
          {s && <DialogDescription>{t("Series.works", { count: s.works.length })}</DialogDescription>}
        </DialogHeader>
        {series.isError && <p className="text-sm text-destructive">{t("SeriesScreen.failed", { error: series.error.message })}</p>}
        {s && (
          <div className="flex flex-col gap-4">
            <section className="flex flex-col gap-2">
              <h4 className="text-sm font-semibold">{t("Series.sites")}</h4>
              {s.sites.length === 0 && <p className="text-sm text-muted-foreground">{t("Series.none")}</p>}
              {s.sites.map((site) => (
                <SiteOfSeries key={`${site.source}/${site.key}`} site={site} />
              ))}
            </section>
            <section className="flex flex-col gap-2">
              <h4 className="text-sm font-semibold" title={t("Series.orderHint")}>
                {t("Series.order")}
              </h4>
              <SeriesWorks
                type={type}
                series={s}
                onMerge={(first, second) => setMerging({ first, second })}
                trailing={(w) =>
                  // Of a work a site's series has, the site's word stands: nothing to take out
                  w.said &&
                  !w.bySite && (
                    <AsyncButton variant="ghost" size="xs" title={t("Series.takeOutHint")} onClick={() => takeOut(w.work.source, w.work.item)}>
                      {t("Series.takeOut")}
                    </AsyncButton>
                  )
                }
              />
            </section>
          </div>
        )}
        {merging && <MergeWorks type={type} first={merging.first} second={merging.second} onClose={() => setMerging(null)} />}
      </DialogContent>
    </Dialog>
  );
}

/** What one site says of the series: its title with its page, its status and dates, its description. */
function SiteOfSeries({ site }: { site: SeriesSite }) {
  const { t } = useTranslation("series");
  return (
    <div className="flex flex-col gap-1 rounded-lg border px-3 py-2 text-sm">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
        <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(site.source)}>
          {sourceShort(site.source)}
        </span>
        {site.url ? (
          <a href={site.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-medium underline-offset-2 hover:underline" title={t("Series.site")}>
            {site.title} <ExternalLinkIcon className="size-3" />
          </a>
        ) : (
          <span className="font-medium">{site.title}</span>
        )}
        <span className="text-xs text-muted-foreground tabular-nums">{t("Series.works", { count: site.works })}</span>
        {site.statusKey && <span className="text-xs">{label(site.statusKey)}</span>}
        {site.begunAt && <span className="text-xs text-muted-foreground">{t("Series.begun", { date: formatDay(site.begunAt) })}</span>}
        {site.updatedAt && <span className="text-xs text-muted-foreground">{t("Series.updated", { date: formatDay(site.updatedAt) })}</span>}
      </div>
      {!site.seen && <p className="text-xs text-muted-foreground">{t("Series.notSeen")}</p>}
      {site.description && <p className="whitespace-pre-line text-muted-foreground">{site.description}</p>}
    </div>
  );
}

/**
 * The window of the series the address names, `#series=<type>/<key>`: a link followed opens it over
 * whatever is open; a work opened from it opens over it. Closed, the address names the work dialog
 * left on top, or none.
 */
export function HashSeriesDialog() {
  const types = useTypes();
  const [opened, setOpened] = useState(readSeriesHash);
  useEffect(() => {
    // A work opened from the series leaves it open under the work
    const onHash = () => {
      const next = readSeriesHash();
      if (next) setOpened(next);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const close = useCallback(() => {
    setOpened(null);
    writeTopHash();
  }, []);
  const type = opened ? types.data?.find((x) => x.id === opened.type) : undefined;
  if (!opened || !type) return null;
  return <SeriesDialog key={`${opened.type}/${opened.key}`} type={type} seriesRef={opened} onClose={close} />;
}
