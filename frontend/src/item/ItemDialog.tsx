import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronLeftIcon, ChevronRightIcon, DownloadIcon, ExternalLinkIcon, Loader2Icon, RefreshCwIcon } from "lucide-react";
import { api, unwrap, type ContentTypeInfo, type Grade, type ItemDetails, type ItemSummary, type SourceInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { gradeLabel, useSource } from "@/contenttype/types";
import { FacetCorrections } from "@/correction/FacetCorrections";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { Links } from "@/correction/Links";
import { titleField, useCorrections, type Corrections } from "@/correction/useCorrections";
import { ago, compact, formatDate } from "@/i18n";
import { FeatureBars } from "@/model/FeatureBars";
import { Parts } from "@/part/Parts";
import { PictureMatches, Pictures } from "@/picture/Pictures";
import { ReviewMatches, Reviews } from "@/review/Reviews";
import { ItemNumbers } from "./ItemNumbers";
import { ItemTexts } from "./ItemTexts";
import { itemKey } from "./lists";
import { Cover, GRADE_TEXT, KEY_GRADES, PreviousRating, RatingButtons, ScoreBadge, SignalBadges } from "./pieces";
import { useRefreshItem } from "./useRefreshItem";

type Props = {
  items: ItemSummary[];
  index: number | null;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (summary: ItemSummary, grade: Grade | null) => void;
  /** The graded work leaves the list, so the next one takes its place under the same index. */
  ratedLeaves: boolean;
};

/**
 * One work at a time, for going through the list: 1..5 grade and move on, ←/→ step back and
 * forth without answering, o opens the site, Esc closes.
 */
export function ItemDialog({ items, index, total, onIndex, onRate, ratedLeaves }: Props) {
  const summary = index !== null ? items[index] : undefined;
  const of = useSource(summary?.source);

  useEffect(() => {
    if (index !== null && index >= items.length) onIndex(items.length > 0 ? items.length - 1 : null);
  }, [index, items.length, onIndex]);

  const rate = (grade: Grade | null) => {
    if (!summary || index === null) return;
    onRate(summary, grade);
    if (grade !== null && !ratedLeaves) onIndex(Math.min(index + 1, items.length - 1));
  };

  useEffect(() => {
    if (!summary || index === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if ((e.target as HTMLElement).closest("input, textarea, select")) return;
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
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  return (
    <Dialog open={summary !== undefined} onOpenChange={(open) => !open && onIndex(null)}>
      <DialogContent className="flex h-[92vh] max-w-6xl flex-col gap-0 overflow-hidden p-0">
        {summary && index !== null && of && (
          <Shell
            // A new work is a new dialog: the editors of the last one do not stay open
            key={`${summary.source}/${summary.item}`}
            type={of.type}
            source={of.source}
            summary={summary}
            index={index}
            count={items.length}
            total={total}
            onIndex={onIndex}
            onRate={rate}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function Shell({
  type,
  source,
  summary,
  index,
  count,
  total,
  onIndex,
  onRate,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  summary: ItemSummary;
  index: number;
  count: number;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (grade: Grade | null) => void;
}) {
  const ref = { source: summary.source, item: summary.item };
  const details = useQuery({
    queryKey: itemKey(ref),
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: ref } })),
  });
  const corrections = useCorrections(ref, type.id);
  const [editingTitle, setEditingTitle] = useState(false);
  const d = details.data;

  return (
    <>
      <div className="relative h-56 shrink-0 overflow-hidden sm:h-72">
        <Cover typeId={type.id} summary={summary} full className="size-full" />
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/90 via-black/30 to-black/10" />
        {type.sources.length > 1 && (
          <span className="absolute top-3 left-4 rounded-md bg-background/85 px-2 py-0.5 text-xs font-medium text-foreground shadow-sm">
            {source.title}
          </span>
        )}
        <div className="absolute inset-x-0 bottom-0 flex items-end gap-4 p-4 text-white">
          <div className="min-w-0 flex-1">
            {editingTitle ? (
              <FieldEditor
                value={summary.title}
                corrected={d?.titleCorrected ?? false}
                onSave={(content) => corrections.setField(titleField, content)}
                onReset={() => corrections.resetField(titleField)}
                onClose={() => setEditingTitle(false)}
                className="max-w-2xl [&_button]:text-foreground"
              />
            ) : (
              <div className="group/title flex items-center gap-1">
                <DialogTitle className="text-2xl leading-tight font-semibold text-white drop-shadow">{summary.title}</DialogTitle>
                {d?.titleCorrected && <CorrectedMark className="border-white/50 text-white/80" />}
                <EditButton
                  onClick={() => setEditingTitle(true)}
                  title="Исправить название"
                  className="text-white/70 opacity-0 transition-opacity group-hover/title:opacity-100 hover:text-white focus-visible:opacity-100 pointer-coarse:opacity-100"
                />
              </div>
            )}
            <DialogDescription className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-white/85">
              {summary.version && <span className="font-medium text-white">{summary.version}</span>}
              <span title={formatDate(summary.updatedAt)}>{ago(summary.updatedAt)}</span>
              {d?.allNumbers.slice(0, 4).map((n) => (
                <span key={n.key}>
                  <span className="text-white/65">{n.label}</span> {compact(n.value)}
                </span>
              ))}
              {d?.reviewCount !== undefined && (
                <span>
                  <span className="text-white/65">{source.reviewsLabel ?? "Отзывы"}</span> {compact(d.reviewCount)}
                </span>
              )}
              <SignalBadges signals={summary.signals} className="[&>*]:border-white/40 [&>*]:text-white" />
            </DialogDescription>
          </div>
          <ScoreBadge type={type} summary={summary} className="h-10 min-w-16 text-lg" />
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {details.isLoading ? (
          <div className="grid gap-3 p-5">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : d ? (
          <Details type={type} source={source} details={d} corrections={corrections} />
        ) : (
          <div className="p-5 text-destructive">Не удалось загрузить подробности</div>
        )}
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-3 border-t bg-muted/40 px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => onIndex(Math.max(index - 1, 0))} disabled={index === 0} title="Предыдущая (←)">
          <ChevronLeftIcon />
        </Button>
        <span className="text-sm tabular-nums text-muted-foreground">
          {index + 1} / {total}
        </span>
        <Button
          variant="ghost"
          size="icon"
          onClick={() => onIndex(Math.min(index + 1, count - 1))}
          disabled={index >= count - 1}
          title="Следующая (→)"
        >
          <ChevronRightIcon />
        </Button>
        <Button variant="outline" size="sm" nativeButton={false} render={<a href={summary.url} target="_blank" rel="noreferrer" />} title="Открыть на сайте (o)">
          <ExternalLinkIcon /> {source.title}
        </Button>
        {d?.canRefresh && <RefreshButton item={ref} />}
        <PreviousRating type={type} summary={summary} />
        <RatingButtons
          type={type}
          value={summary.grade as Grade | undefined}
          onRate={onRate}
          size="lg"
          labels
          className="ml-auto w-full sm:w-[40rem]"
        />
      </div>
    </>
  );
}

function Details({
  type,
  source,
  details: d,
  corrections,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  corrections: Corrections;
}) {
  const ref = { source: d.summary.source, item: d.summary.item };
  const positive = d.explanation.filter((e) => e.contribution > 0);
  const negative = d.explanation.filter((e) => e.contribution < 0);
  const nothing = d.texts.length === 0 && d.pictures.length === 0 && d.reviews.length === 0 && d.parts.length === 0;

  return (
    <div className="grid gap-6 p-5 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="flex min-w-0 flex-col gap-5">
        {nothing && d.canRefresh && <NotFetched item={ref} />}
        <ItemTexts texts={d.texts} source={source} corrections={corrections} />
        <Pictures item={ref} pictures={d.pictures} />
        <PictureMatches item={ref} matches={d.pictureMatches} />
        <Reviews item={ref} label={source.reviewsLabel ?? "Отзывы"} reviews={d.reviews} total={d.reviewCount} />
        <ReviewMatches item={ref} matches={d.reviewMatches} />
        <Parts item={ref} label={source.partsLabel ?? "Части"} parts={d.parts} />
      </div>

      <aside className="flex flex-col gap-5">
        {d.explanation.length > 0 && (
          <section className="flex flex-col gap-3">
            <h4 className="text-sm font-semibold">Почему такой прогноз</h4>
            {positive.length > 0 && <FeatureBars items={positive} />}
            {negative.length > 0 && <FeatureBars items={negative} />}
          </section>
        )}
        <Separator />
        <FacetCorrections typeId={type.id} source={source} facets={d.allFacets} corrections={corrections} />
        <ItemNumbers numbers={d.allNumbers} source={source} corrections={corrections} />
        <Separator />
        <Links type={type} summary={d.summary} corrections={corrections} />
        {d.ratings.length > 0 && (
          <section>
            <h4 className="mb-2 text-sm font-semibold">Мои оценки</h4>
            <ul className="flex flex-col gap-1 text-sm">
              {d.ratings.map((r) => (
                <li key={`${r.version}-${r.ratedAt}`} className="flex justify-between gap-2">
                  <span className="truncate" title={formatDate(r.ratedAt)}>
                    {r.version || formatDate(r.ratedAt)}
                  </span>
                  <span className={GRADE_TEXT[r.grade as Grade]}>
                    {r.grade} · {gradeLabel(type, r.grade as Grade)}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </aside>
    </div>
  );
}

/** Nothing of the work is here yet: its page has not come in — fetch it now. */
function NotFetched({ item }: { item: { source: string; item: string } }) {
  const refresh = useRefreshItem(item);
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
      Страница этой работы ещё не загружена: нет ни текстов, ни картинок.
      <Button onClick={() => refresh.mutate()} disabled={refresh.isPending}>
        {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <DownloadIcon />}
        {refresh.isPending ? "Загружаю…" : "Загрузить страницу"}
      </Button>
    </div>
  );
}

/** Downloads the page again: after a login was set, or to see a fresh update before the next job. */
function RefreshButton({ item }: { item: { source: string; item: string } }) {
  const refresh = useRefreshItem(item);
  return (
    <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending} title="Скачать страницу работы заново">
      {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
      Обновить
    </Button>
  );
}
