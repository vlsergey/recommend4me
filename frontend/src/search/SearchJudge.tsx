import { useState } from "react";
import { CheckIcon, EqualApproximatelyIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { SearchFit } from "@/api/client";
import { cn } from "@/lib/utils";

/**
 * "Fits the query? ✓ ≈ ✗" under the snippet a work found by a search is shown with: the user's word on
 * how far that snippet answers the line — whole, in part, not at all — never on the work whole, another
 * snippet of it judged apart; what the search learns of and its quality is measured by. Pressed again,
 * the word is taken back. Shown as said at once; the list is not read again for it — its order must not
 * move under the finger.
 */
export function SearchJudge({ value, onJudge }: { value?: SearchFit; onJudge: (fit: SearchFit | null) => Promise<unknown> }) {
  const { t } = useTranslation("search");
  const [said, setSaid] = useState<SearchFit | undefined>(value);
  const press = (fit: SearchFit) => (e: React.MouseEvent) => {
    // The card under it opens on a click: not on this one
    e.stopPropagation();
    const next = said === fit ? undefined : fit;
    setSaid(next);
    onJudge(next ?? null).catch(() => setSaid(said));
  };
  return (
    <div className="flex items-center gap-1 text-xs text-muted-foreground">
      <span>{t("SearchJudge.question")}</span>
      <button
        type="button"
        title={t("SearchJudge.fits")}
        onClick={press("FITS")}
        className={cn("rounded-md p-1 hover:bg-muted", said === "FITS" && "bg-yes/15 text-yes")}
      >
        <CheckIcon className="size-3.5" />
      </button>
      <button
        type="button"
        title={t("SearchJudge.partly")}
        onClick={press("PARTLY")}
        className={cn("rounded-md p-1 hover:bg-muted", said === "PARTLY" && "bg-maybe/20 text-maybe")}
      >
        <EqualApproximatelyIcon className="size-3.5" />
      </button>
      <button
        type="button"
        title={t("SearchJudge.doesNotFit")}
        onClick={press("NOT")}
        className={cn("rounded-md p-1 hover:bg-muted", said === "NOT" && "bg-no/15 text-no")}
      >
        <XIcon className="size-3.5" />
      </button>
    </div>
  );
}
