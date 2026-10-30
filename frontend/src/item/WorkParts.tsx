import type { ReactNode } from "react";
import { DownloadIcon, Loader2Icon, RefreshCwIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { sameItem, type ContentTypeInfo, type Grade, type ItemDetails, type RatingRecord } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { Button } from "@/components/ui/button";
import { gradeLabel } from "@/contenttype/types";
import { formatDate } from "@/i18n/format";
import { sourceShort } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { GRADE_TEXT } from "./pieces";
import { useTakeBackGrade } from "./useRateItem";
import { useRefreshItem } from "./useRefreshItem";

/** A section of the card that fails to draw says so in its place; the grade and the rest of the card stay. */
export function Part({ title, children }: { title: string; children: ReactNode }) {
  const { t } = useTranslation("item");
  return (
    <ErrorBoundary title={t("ItemDialog.partFailed", { part: title.toLowerCase() })} className="m-0 p-3">
      {children}
    </ErrorBoundary>
  );
}

/**
 * The grades of every item of the work, newest first: the item of each when it is not this one,
 * and a cross to take one back — an old version's grade, or one given to the work on another site.
 */
export function Ratings({ type, details: d }: { type: ContentTypeInfo; details: ItemDetails }) {
  const { t } = useTranslation("item");
  const takeBack = useTakeBackGrade(d.summary);
  const itemOf = (r: RatingRecord) =>
    sameItem(r, d.summary)
      ? undefined
      : `${sourceShort(r.source)} · ${d.summary.linked.find((l) => sameItem(l, r))?.title ?? r.item}`;
  return (
    <section>
      <h4 className="mb-2 text-sm font-semibold">{t("Ratings.heading")}</h4>
      <ul className="flex flex-col gap-1 text-sm">
        {d.ratings.map((r) => (
          <li key={`${r.source}/${r.item}-${r.version}-${r.ratedAt}`} className="group/rating flex items-center gap-2">
            <span className="flex min-w-0 flex-1 flex-col">
              <span className={cn("truncate", !r.current && "text-muted-foreground")} title={formatDate(r.ratedAt)}>
                {r.version || formatDate(r.ratedAt)}
                {!r.current && r.version && ` · ${t("Ratings.previousVersion")}`}
              </span>
              {itemOf(r) && <span className="truncate text-xs text-muted-foreground">{itemOf(r)}</span>}
            </span>
            <span className={cn("shrink-0", GRADE_TEXT[r.grade as Grade])}>
              {r.grade} · {gradeLabel(type.id, r.grade as Grade)}
            </span>
            <AsyncButton
              variant="ghost"
              size="icon-xs"
              title={t("Ratings.takeBack")}
              onClick={() => takeBack(r)}
              icon={<XIcon />}
              className="text-muted-foreground opacity-0 transition-opacity group-hover/rating:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
            />
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Nothing of the work is here yet: its page has not come in — fetch it now. */
export function NotFetched({ item }: { item: { source: string; item: string } }) {
  const { t } = useTranslation("item");
  const refresh = useRefreshItem(item);
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
      {t("NotFetched.message")}
      <Button onClick={() => refresh.mutate()} disabled={refresh.isPending}>
        {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <DownloadIcon />}
        {refresh.isPending ? t("NotFetched.loading") : t("NotFetched.load")}
      </Button>
    </div>
  );
}

/** Downloads the page again: after a login was set, or to see a fresh update before the next job. */
export function RefreshButton({ item }: { item: { source: string; item: string } }) {
  const { t } = useTranslation("item");
  const refresh = useRefreshItem(item);
  return (
    <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending} title={t("RefreshButton.title")}>
      {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
      {t("RefreshButton.label")}
    </Button>
  );
}
