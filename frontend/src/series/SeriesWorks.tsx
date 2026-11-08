import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, Grade, ItemRef, SeriesDetails } from "@/api/client";
import { sameItem } from "@/api/client";
import { gradeLabel } from "@/contenttype/types";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { GRADE_TEXT, shownScore } from "@/item/pieces";
import { cn } from "@/lib/utils";

/**
 * THE WORKS OF A NORMALIZED SERIES IN ORDER — every site's numbers held together by pairs — each with
 * its number on every site that numbers it, the user's grade or else the model's prediction, and the
 * mark of a work the user put in it ([trailing]: what goes at the end of its row, its button out). A
 * work opens over whatever is open; the [current] work is told apart.
 */
export function SeriesWorks({
  type,
  series,
  current,
  trailing,
}: {
  type: ContentTypeInfo;
  series: SeriesDetails;
  current?: ItemRef;
  trailing?: (work: SeriesDetails["works"][number]) => React.ReactNode;
}) {
  const { t } = useTranslation("series");
  return (
    <ol className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
      {series.works.map((w, k) => {
        const own = current !== undefined && sameItem(w.work, current);
        return (
          <li key={`${w.work.source}/${w.work.item}`} className={cn("flex items-center gap-2 px-2 py-1 text-sm", own && "bg-muted/60")}>
            <span className="w-6 shrink-0 text-right text-xs text-muted-foreground tabular-nums">{k + 1}.</span>
            {own ? (
              <span className="min-w-0 flex-1 truncate font-medium">
                {w.work.title} <span className="font-normal text-muted-foreground">· {t("Series.this")}</span>
              </span>
            ) : (
              <a href={itemHref(w.work)} className="min-w-0 flex-1 truncate underline-offset-2 hover:underline">
                {w.work.title}
              </a>
            )}
            {w.places.map((p) => (
              <span key={p.source} className="shrink-0 text-xs text-muted-foreground tabular-nums" title={t("Series.place", { source: sourceTitle(p.source) })}>
                {sourceShort(p.source)} #{p.position}
              </span>
            ))}
            {w.said && (
              <span className="shrink-0 rounded bg-muted px-1 text-xs text-muted-foreground" title={t("Series.saidHint")}>
                {t("Series.said")}
              </span>
            )}
            {w.work.grade ? (
              <span className={cn("shrink-0 text-xs", GRADE_TEXT[w.work.grade as Grade])}>
                {w.work.grade} · {gradeLabel(type.id, w.work.grade as Grade)}
              </span>
            ) : (
              w.work.prediction && <span className="shrink-0 text-xs text-muted-foreground tabular-nums">★ {shownScore(w.work.prediction.score)}</span>
            )}
            {trailing?.(w)}
          </li>
        );
      })}
    </ol>
  );
}
