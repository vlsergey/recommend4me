import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { LibraryIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo } from "@/api/client";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { useRefresh } from "@/lib/useRefresh";
import { seriesHref, seriesKey } from "./seriesHash";

/** How many series a page shows, and how many more each "more" adds. */
const PAGE = 50;

/** How often the list is read again till the user chooses ([RefreshSelect]): never — it changes as pages come. */
const EVERY_MS = 0;

/** How long the typing pauses before the search of a series is read. */
const SEARCH_SETTLE_MS = 300;

/**
 * THE SCREEN OF THE NORMALIZED SERIES of a content type: every series of every site and the works the
 * user said to be one series, made one by the works they share — the most works first, found by a
 * title. A series opens in a window of its own ([SeriesDialog][./SeriesDialog]), with what every site
 * says of it and its works in order.
 */
export function SeriesScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("series");
  const [every, setEvery] = useRefresh("series.refresh", EVERY_MS);
  const [typed, setTyped] = useState("");
  const [search, setSearch] = useState("");
  const [limit, setLimit] = useState(PAGE);
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(typed.trim());
      setLimit(PAGE);
    }, SEARCH_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  const list = useQuery({
    queryKey: [...seriesKey(type.id), "list", search, limit],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/series", { params: { path: { type: type.id }, query: { search: search || undefined, offset: 0, limit } } })),
    placeholderData: keepPreviousData,
    refetchInterval: every || false,
  });
  const data = list.data;
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-4">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <LibraryIcon className="size-5" /> {t("SeriesScreen.title")}
        </h1>
        <p className="text-sm text-muted-foreground">{t("SeriesScreen.description")}</p>
      </header>
      <div className="flex flex-wrap items-center gap-2">
        <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={t("SeriesScreen.search")} aria-label={t("SeriesScreen.search")} className="max-w-xs" />
        {data && <span className="text-sm text-muted-foreground tabular-nums">{t("SeriesScreen.total", { count: data.total })}</span>}
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </div>
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("SeriesScreen.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("SeriesScreen.failed", { error: list.error.message })}</p>}
      {data && data.series.length === 0 && <p className="text-sm text-muted-foreground">{t("SeriesScreen.none")}</p>}
      {data && data.series.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {data.series.map((s) => (
            <li key={s.key} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 px-3 py-1.5">
              <a href={seriesHref(type.id, s.key)} className="min-w-0 truncate font-medium underline-offset-2 hover:underline" title={t("SeriesScreen.openHint")}>
                {s.title}
              </a>
              {s.sites.map((site) => (
                <span key={`${site.source}/${site.key}`} className="text-xs text-muted-foreground" title={sourceTitle(site.source)}>
                  {sourceShort(site.source)}
                  {site.title !== s.title && `: ${site.title}`}
                </span>
              ))}
              <span className="ml-auto text-xs text-muted-foreground tabular-nums">{t("Series.works", { count: s.works })}</span>
            </li>
          ))}
        </ul>
      )}
      {data && data.total > data.series.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("SeriesScreen.more", { count: Math.min(PAGE, data.total - data.series.length) })}
        </Button>
      )}
    </div>
  );
}
