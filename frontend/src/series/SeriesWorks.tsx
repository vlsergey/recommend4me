import { useState } from "react";
import { createPortal } from "react-dom";
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
 * by its handle — a mouse or a finger — onto another of no site in common to make the two one work, asked
 * first.
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
  // Where the pointer is while a work is dragged: its title follows it — a finger hides the row it holds
  const [at, setAt] = useState<{ x: number; y: number } | null>(null);
  const draggedWork = dragged !== null ? series.works[dragged] : undefined;
  const end = () => {
    setDragged(null);
    setOver(null);
    setAt(null);
  };
  /** The row under the pointer that the dragged work may be dropped on; null of none. */
  const rowAt = (x: number, y: number): number | null => {
    const row = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-series-row]");
    const k = row ? Number(row.dataset.seriesRow) : Number.NaN;
    return draggedWork && !Number.isNaN(k) && mergeable(draggedWork, series.works[k]) ? k : null;
  };
  return (
    <ol className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
      {series.works.map((w, k) => {
        const own = current !== undefined && sameItem(w.work, current);
        const target = draggedWork !== undefined && mergeable(draggedWork, w);
        return (
          <li
            key={`${w.work.source}/${w.work.item}`}
            data-series-row={k}
            className={cn(
              "flex items-center gap-2 px-2 py-1 text-sm",
              own && "bg-muted/60",
              dragged === k && "opacity-50",
              draggedWork !== undefined && !target && dragged !== k && "opacity-60",
              over === k && "bg-primary/15 outline outline-2 -outline-offset-2 outline-primary",
            )}
          >
            {onMerge && (
              // Dragged by the pointer — a mouse or a finger alike: the browser's own dragging is none on a tablet's
              // touch. The handle takes the touch whole, the list does not scroll under it
              <span
                className="shrink-0 cursor-grab touch-none p-1 text-muted-foreground select-none"
                title={t("Merge.dragHint")}
                onPointerDown={(e) => {
                  e.currentTarget.setPointerCapture(e.pointerId);
                  setDragged(k);
                  setAt({ x: e.clientX, y: e.clientY });
                }}
                onPointerMove={(e) => {
                  if (dragged !== k) return;
                  setAt({ x: e.clientX, y: e.clientY });
                  setOver(rowAt(e.clientX, e.clientY));
                }}
                onPointerUp={(e) => {
                  const onto = dragged === k ? rowAt(e.clientX, e.clientY) : null;
                  if (draggedWork && onto !== null && onMerge) onMerge(refOf(draggedWork), refOf(series.works[onto]));
                  end();
                }}
                onPointerCancel={end}
              >
                <GripVerticalIcon className="size-4" />
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
      {draggedWork &&
        at &&
        // Over the page itself: a dialog's own transform would place a fixed element by the dialog, not the screen
        createPortal(
          <div
            aria-hidden
            className="pointer-events-none fixed z-[100] max-w-xs truncate rounded-md border bg-popover px-2 py-1 text-sm shadow-md"
            style={{ left: at.x + 12, top: at.y + 12 }}
          >
            {draggedWork.work.title}
          </div>,
          document.body,
        )}
    </ol>
  );
}
