import { useState } from "react";
import { GripVerticalIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, Grade, ItemRef, SeriesDetails } from "@/api/client";
import { sameItem } from "@/api/client";
import { gradeLabel } from "@/contenttype/types";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { GRADE_TEXT, shownScore } from "@/item/pieces";
import { cn } from "@/lib/utils";

type SeriesWork = SeriesDetails["works"][number];

/** Whether two works may be one: of no site in common — two items of one site are two works of it. */
function mergeable(a: SeriesWork, b: SeriesWork): boolean {
  return a !== b && !a.sources.some((s) => b.sources.includes(s));
}

const refOf = (w: SeriesWork): ItemRef => ({ source: w.work.source, item: w.work.item });

/**
 * THE WORKS OF A NORMALIZED SERIES IN ORDER — every site's numbers held together by pairs — each with
 * its number on every site that numbers it, the user's grade or else the model's prediction, and the
 * mark of a work the user alone put in it ([trailing]: what goes at the end of its row, its button out). A
 * work opens over whatever is open; the [current] work is told apart. Given [onMerge], a work is dragged
 * onto another of no site in common to make the two one work, asked first.
 */
export function SeriesWorks({
  type,
  series,
  current,
  trailing,
  onMerge,
}: {
  type: ContentTypeInfo;
  series: SeriesDetails;
  current?: ItemRef;
  trailing?: (work: SeriesWork) => React.ReactNode;
  onMerge?: (dragged: ItemRef, onto: ItemRef) => void;
}) {
  const { t } = useTranslation("series");
  const [dragged, setDragged] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const draggedWork = dragged !== null ? series.works[dragged] : undefined;
  const end = () => {
    setDragged(null);
    setOver(null);
  };
  return (
    <ol className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
      {series.works.map((w, k) => {
        const own = current !== undefined && sameItem(w.work, current);
        const target = draggedWork !== undefined && mergeable(draggedWork, w);
        return (
          <li
            key={`${w.work.source}/${w.work.item}`}
            draggable={onMerge !== undefined}
            onDragStart={(e) => {
              e.dataTransfer.effectAllowed = "link";
              setDragged(k);
            }}
            onDragEnd={end}
            onDragOver={(e) => {
              if (!target) return;
              e.preventDefault();
              e.dataTransfer.dropEffect = "link";
              setOver(k);
            }}
            onDragLeave={() => over === k && setOver(null)}
            onDrop={(e) => {
              e.preventDefault();
              if (draggedWork && target && onMerge) onMerge(refOf(draggedWork), refOf(w));
              end();
            }}
            className={cn(
              "flex items-center gap-2 px-2 py-1 text-sm",
              own && "bg-muted/60",
              dragged === k && "opacity-50",
              draggedWork !== undefined && !target && dragged !== k && "opacity-60",
              over === k && "bg-primary/15 outline outline-2 -outline-offset-2 outline-primary",
            )}
          >
            {onMerge && (
              <span className="shrink-0 cursor-grab text-muted-foreground" title={t("Merge.dragHint")}>
                <GripVerticalIcon className="size-3.5" />
              </span>
            )}
            <span className="w-6 shrink-0 text-right text-xs text-muted-foreground tabular-nums">{k + 1}.</span>
            {own ? (
              <span className="min-w-0 flex-1 truncate font-medium">
                {w.work.title} <span className="font-normal text-muted-foreground">· {t("Series.this")}</span>
              </span>
            ) : (
              <a href={itemHref(w.work)} draggable={false} className="min-w-0 flex-1 truncate underline-offset-2 hover:underline">
                {w.work.title}
              </a>
            )}
            {w.places.map((p) => (
              <span key={p.source} className="shrink-0 text-xs text-muted-foreground tabular-nums" title={t("Series.place", { source: sourceTitle(p.source) })}>
                {sourceShort(p.source)} #{p.position}
              </span>
            ))}
            {/* Of a work a site's series has, the site's word: the user's adds nothing */}
            {w.said && !w.bySite && (
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
