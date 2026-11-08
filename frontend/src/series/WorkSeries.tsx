import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, ItemRef, SeriesDetails } from "@/api/client";
import { seriesHref } from "./seriesHash";
import { SeriesWorks } from "./SeriesWorks";

/**
 * THE SERIES OF A WORK ON ITS SCREEN: its title, a link to its window ([SeriesDialog][./SeriesDialog])
 * with what every site says of it, and its works in order, the work itself among them.
 */
export function WorkSeries({ type, series, current }: { type: ContentTypeInfo; series: SeriesDetails; current: ItemRef }) {
  const { t } = useTranslation("series");
  return (
    <section className="flex flex-col gap-1">
      <h4 className="flex flex-wrap items-baseline gap-x-2 text-sm font-semibold">
        <a href={seriesHref(type.id, series.key)} className="underline-offset-2 hover:underline" title={t("Series.open")}>
          {series.title}
        </a>
        <span className="font-normal text-muted-foreground tabular-nums">{t("Series.works", { count: series.works.length })}</span>
      </h4>
      <SeriesWorks type={type} series={series} current={current} />
    </section>
  );
}
