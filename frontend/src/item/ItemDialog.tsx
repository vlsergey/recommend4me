import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import type { Grade, ItemSummary } from "@/api/client";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import { useSource } from "@/contenttype/types";
import { useStackedDialog } from "./dialogStack";
import { KEY_GRADES } from "./pieces";
import { WorkScreen, WORK_SCREEN_CLASS } from "./WorkScreen";

type Props = {
  items: ItemSummary[];
  index: number | null;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (summary: ItemSummary, grade: Grade | null) => unknown;
  /** The graded work leaves the list, so the next one takes its place under the same index. */
  ratedLeaves: boolean;
  /** Found by a search: the user's word on whether the work answers the line. */
  onJudge?: (summary: ItemSummary, relevant: boolean | null) => Promise<unknown>;
};

/**
 * One work at a time, for going through the list, on a screen of its own ([WorkScreen]): 1..5 grade
 * and move on, ←/→ step back and forth without answering, o opens the site, Esc closes. An answer
 * never moves on.
 */
export function ItemDialog({ items, index, total, onIndex, onRate, ratedLeaves, onJudge }: Props) {
  const { t } = useTranslation("item");
  const summary = index !== null ? items[index] : undefined;
  const of = useSource(summary?.source);

  useEffect(() => {
    if (index !== null && index >= items.length) onIndex(items.length > 0 ? items.length - 1 : null);
  }, [index, items.length, onIndex]);

  // The address names the open work, for a link to it, and the keys are this dialog's — while it
  // is on top: a related work's dialog opened over it takes both, and gives them back closed
  const onTop = useStackedDialog(summary ? { source: summary.source, item: summary.item } : null);

  const rate = (grade: Grade | null) => {
    if (!summary || index === null) return;
    const saved = onRate(summary, grade);
    if (grade !== null && !ratedLeaves) onIndex(Math.min(index + 1, items.length - 1));
    return saved;
  };

  const popup = useRef<HTMLDivElement>(null);

  // Taken before the dialog: its popup stops the arrow keys on their way up
  useEffect(() => {
    if (!summary || index === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (!onTop()) return;
      const target = e.target as HTMLElement;
      if (target.closest("input, textarea, select, [role=menu], [role=tablist]")) return;
      // A window of its own over the screen — the tags by the text, a sheet — keeps its keys
      const window_ = target.closest("[role=dialog]");
      if (window_ && window_ !== popup.current) return;
      if (e.key === "ArrowRight" || e.key === "j") {
        onIndex(Math.min(index + 1, items.length - 1));
        e.preventDefault();
      } else if (e.key === "ArrowLeft" || e.key === "k") {
        onIndex(Math.max(index - 1, 0));
        e.preventDefault();
      } else if (KEY_GRADES[e.key]) {
        rate(KEY_GRADES[e.key]);
        e.preventDefault();
      } else if (e.key === "o") {
        window.open(summary.url, "_blank", "noreferrer");
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  });

  return (
    // Esc closes the dialog on top alone, not the one under it too
    <Dialog open={summary !== undefined} onOpenChange={(open) => !open && onTop() && onIndex(null)}>
      <DialogContent ref={popup} className={WORK_SCREEN_CLASS}>
        {summary && index !== null && of && (
          // A work that fails to draw says so in the dialog, and the next one is drawn anew
          <ErrorBoundary resetKey={`${summary.source}/${summary.item}`} title={t("ItemDialog.failed")}>
            <WorkScreen
              // A new work is a new screen: the editors of the last one do not stay open
              key={`${summary.source}/${summary.item}`}
              type={of.type}
              source={of.source}
              summary={summary}
              index={index}
              count={items.length}
              total={total}
              onIndex={onIndex}
              onRate={rate}
              onJudge={onJudge && ((relevant) => onJudge(summary, relevant))}
            />
          </ErrorBoundary>
        )}
      </DialogContent>
    </Dialog>
  );
}
