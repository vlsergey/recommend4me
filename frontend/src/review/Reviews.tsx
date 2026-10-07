import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { StarIcon } from "lucide-react";
import { api, ensureOk, type ItemRef, type ReviewInfo, type ReviewMatch } from "@/api/client";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/i18n";
import { itemKey } from "@/item/lists";
import { InfluenceBadge, MarkButtons, MatchVerdict, StrengthBadge } from "@/mark/MarkButtons";
import { cn } from "@/lib/utils";

const FIRST_SHOWN = 8;

/**
 * Other readers' or players' reviews that move the work most, either way, each with its points:
 * the score as it is minus the score without that review. Long ones are cut, a click shows them whole.
 */
export function Reviews({
  item,
  label,
  reviews,
  total,
  marking = true,
}: {
  item: ItemRef;
  label: string;
  reviews: ReviewInfo[];
  total?: number;
  /** The marking: each review takes the user's mark, and the marks are explained. */
  marking?: boolean;
}) {
  const [all, setAll] = useState(false);
  if (reviews.length === 0) return null;
  const shown = all ? reviews : reviews.slice(0, FIRST_SHOWN);
  const moving = reviews.some((r) => r.influence != null);
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">
        {label}
        {total !== undefined && <span className="ml-1 font-normal text-muted-foreground">{total}</span>}
        <span className="ml-1 text-xs font-normal text-muted-foreground">· {moving ? "сильнее всего сдвигающие прогноз" : "самые новые"}</span>
      </h4>
      {marking && (
        <p className="mb-2 text-xs text-muted-foreground">
          {moving && "Число — на сколько баллов вашей шкалы, прогноз как есть минус прогноз без этого отзыва. "}
          Отзыв, который говорит, почему вы выбрали бы работу или отбросили её, отметьте 👍 или 👎 — модель будет искать похожие
          в других работах.
        </p>
      )}
      <div className="flex flex-col gap-2">
        {shown.map((r) => (
          <ReviewCard key={r.reviewId} item={item} review={r} marking={marking} />
        ))}
      </div>
      {reviews.length > shown.length && (
        <Button variant="ghost" size="sm" className="mt-1" onClick={() => setAll(true)}>
          Ещё {reviews.length - shown.length}
        </Button>
      )}
    </section>
  );
}

function ReviewCard({ item, review, marking }: { item: ItemRef; review: ReviewInfo; marking: boolean }) {
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const mark = async (value: number | null) => {
    const params = { path: { source: item.source, item: item.item, reviewId: review.reviewId } };
    ensureOk(
      value === null
        ? await api.DELETE("/api/items/{source}/{item}/reviews/{reviewId}/mark", { params })
        : await api.PUT("/api/items/{source}/{item}/reviews/{reviewId}/mark", { params, body: { mark: value } }),
    );
    await queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  const long = review.content.length > 400;
  const influence = review.influence;
  return (
    <div
      className={cn(
        "rounded-lg border bg-card p-3 text-sm",
        review.mark === 1 && "border-yes ring-1 ring-yes",
        review.mark === -1 && "border-no ring-1 ring-no",
      )}
    >
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        {review.stars != null && (
          <span className="inline-flex items-center gap-0.5 text-foreground">
            <StarIcon className="size-3 fill-current" /> {review.stars.toLocaleString("ru-RU", { maximumFractionDigits: 1 })}
          </span>
        )}
        {review.author && <span className="truncate">{review.author}</span>}
        {review.postedAt && <span>{formatDate(review.postedAt)}</span>}
        {influence != null && (
          <InfluenceBadge
            influence={influence}
            className="ml-auto shadow-none"
            title="Прогноз как есть минус прогноз без этого отзыва, в баллах вашей шкалы"
          />
        )}
        {marking && (
          <MarkButtons
            mark={review.mark}
            onMark={mark}
            className={cn(
              "[&_button]:bg-muted [&_button]:text-foreground [&_button:hover]:bg-muted-foreground/20",
              influence == null && "ml-auto",
            )}
          />
        )}
      </div>
      <p
        className={cn("leading-relaxed whitespace-pre-line text-foreground/90", long && !open && "line-clamp-4 cursor-pointer")}
        onClick={() => long && setOpen(!open)}
      >
        {review.content}
      </p>
    </div>
  );
}

/**
 * The reviews of this work that read like reviews the user marked in other works: this work's
 * first, the marked one under it, and how far from chance the likeness is.
 */
export function ReviewMatches({ item, matches }: { item: ItemRef; matches: ReviewMatch[] }) {
  if (matches.length === 0) return null;
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">Похоже на отмеченные вами отзывы</h4>
      <p className="mb-2 text-xs text-muted-foreground">
        Отзыв об этой работе и под ним — отмеченный вами отзыв о другой. Число — насколько сходство неслучайно, как у
        картинок; ✓ и ✕ — тоже как у картинок.
      </p>
      <div className="flex flex-col gap-3">
        {matches.map((m) => (
          <div key={`${m.reviewId}-${m.markSource}/${m.markItem}-${m.markReviewId}`} className={cn("flex flex-col gap-1.5", m.verdict === -1 && "opacity-40")}>
            <p className="line-clamp-4 rounded-lg border bg-card p-3 text-sm leading-relaxed whitespace-pre-line text-foreground/90">
              {m.content}
            </p>
            <div className="flex items-start gap-2 pl-4">
              <span className="mt-2">
                <StrengthBadge
                  mark={m.mark}
                  strength={m.strength}
                  title={m.mark > 0 ? "Похоже на отзыв, из-за которого вы выбрали бы работу" : "Похоже на отзыв, из-за которого вы отбросили бы работу"}
                />
              </span>
              <figure className={cn("min-w-0 flex-1 rounded-lg border p-2 text-xs text-muted-foreground", m.mark > 0 ? "border-yes/60" : "border-no/60")}>
                <p className="line-clamp-3 whitespace-pre-line">{m.markContent}</p>
                <figcaption className="mt-1 flex items-center gap-1">
                  <span className="min-w-0 flex-1 truncate font-medium">{m.markTitle}</span>
                  <MatchVerdict
                    item={item}
                    kind="REVIEW"
                    mark={{ source: m.markSource, item: m.markItem }}
                    markRef={m.markReviewId}
                    matchRef={m.reviewId}
                    verdict={m.verdict}
                  />
                </figcaption>
              </figure>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
