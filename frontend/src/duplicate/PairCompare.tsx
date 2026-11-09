import { useState } from "react";
import { ExternalLinkIcon, LibraryBigIcon, UserIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { DuplicatePair, DuplicateSort, DuplicateWork, SizeNumber } from "@/api/client";
import { number, percent } from "@/i18n/format";
import { intlTag } from "@/i18n/i18n";
import { label, sourceShort, sourceTitle, valueName } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { pressKeys } from "@/lib/pressKeys";
import { cn } from "@/lib/utils";

/**
 * TWO WORKS COMPARED, as the screen of duplicates shows a pair and every other place that asks whether two
 * works are one: what tells how alike they are ([PairFacts]) and the two side by side ([WorkSide]).
 */

/**
 * How alike a pair is: the chance they are one work and one series, how alike each layer of theirs is,
 * the [priority] of the list's sort when it says something of the work, whether the dictionary of authors
 * gives them one author. A pair the last search did not keep has no layers: its works alone.
 */
export function PairFacts({ pair: p, priority }: { pair: DuplicatePair; priority?: DuplicateSort }) {
  const { t } = useTranslation("duplicate");
  return (
    <>
      {p.chance != null && (
        <span className="font-semibold text-foreground tabular-nums" title={t("PairRow.chanceHint")}>
          {percent(p.chance)}
        </span>
      )}
      {p.seriesChance != null && (
        <span className="flex items-center gap-1 font-semibold text-foreground tabular-nums" title={t("PairRow.seriesChanceHint")}>
          <LibraryBigIcon className="size-3.5" /> {percent(p.seriesChance)}
        </span>
      )}
      {p.title != null && <span className="tabular-nums">{t("PairRow.title", { value: percent(p.title) })}</span>}
      {p.description != null && <span className="tabular-nums">{t("PairRow.description", { value: percent(p.description) })}</span>}
      {p.facets?.map((f) => (
        <span key={f.key} className="tabular-nums" title={t("PairRow.facetsHint")}>
          {t("PairRow.facet", { name: label(f.labelKey), value: percent(f.value) })}
        </span>
      ))}
      {p.size != null && (
        <span className="tabular-nums" title={t("PairRow.sizeHint")}>
          {t("PairRow.size", { value: percent(p.size) })}
        </span>
      )}
      {priority && p.priority != null && p.priority > 0 && (
        // Of the sort by size the priority is words: a whole number, not a score of two digits
        <span className="tabular-nums" title={t(priority === "SIZE" ? "PairRow.prioritySizeHint" : "PairRow.priorityHint")}>
          {t("PairRow.priority", { value: priority === "SIZE" ? number(Math.round(p.priority)) : p.priority.toFixed(2) })}
        </span>
      )}
      {p.sameAuthor && (
        <span className="flex items-center gap-1 text-foreground" title={t("PairRow.sameAuthorHint")}>
          <UserIcon className="size-3.5" /> {t("PairRow.sameAuthor")}
        </span>
      )}
    </>
  );
}

/**
 * One work of a pair: its site, its title opening the work, its page on the site, its authors, fandoms,
 * status and size, its description and after it the site's own line of its characters and pairings,
 * folded together.
 */
export function WorkSide({ work: w }: { work: DuplicateWork }) {
  const { t } = useTranslation("duplicate");
  const [whole, setWhole] = useState(false);
  const names = [w.authors.join(", "), w.fandoms.join(", "), w.status?.map(valueName).join(", ")].filter(Boolean).join(" · ");
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex items-baseline gap-2">
        <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
          {sourceShort(w.source)}
        </span>
        <a href={itemHref({ source: w.source, item: w.item })} className="min-w-0 font-semibold underline-offset-2 hover:underline">
          {w.title}
        </a>
        <a href={w.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("WorkSide.page")}>
          <ExternalLinkIcon className="size-3.5" />
        </a>
      </div>
      {(names || w.words || w.chars) && (
        <div className="text-xs text-muted-foreground">
          {names}
          {names && (w.words || w.chars) && " · "}
          {w.words && <Size size={w.words} word="words" />}
          {w.words && w.chars && "; "}
          {w.chars && <Size size={w.chars} word="chars" />}
        </div>
      )}
      {(w.description || w.universeLine) && (
        // Text to select and copy: unfolded or folded by a press that selects nothing. The site's own line of
        // the characters and pairings after the description, folded with it: one flow of lines, clamped together
        // biome-ignore lint/a11y/useSemanticElements: text to select and copy, which a button's text cannot be
        <div
          role="button"
          tabIndex={0}
          className={cn("cursor-pointer text-sm whitespace-pre-line select-text text-muted-foreground", !whole && "line-clamp-4")}
          title={t(whole ? "WorkSide.fold" : "WorkSide.unfold")}
          onClick={() => {
            if (!window.getSelection()?.toString()) setWhole(!whole);
          }}
          onKeyDown={pressKeys(() => setWhole(!whole))}
        >
          {w.description}
          {w.description && w.universeLine && "\n"}
          {w.universeLine && <span className="text-foreground/80">{w.universeLine}</span>}
        </div>
      )}
    </div>
  );
}

/** How many significant digits a size keeps: the site's number three, one worked out two — it is known no better. */
const STATED_DIGITS = 3;
const WORKED_OUT_DIGITS = 2;

/** How long a work is, short, in thousands or millions: a number worked out after a "≈". */
function Size({ size, word }: { size: SizeNumber; word: "words" | "chars" }) {
  const { t } = useTranslation("duplicate");
  const count = Math.round(size.value);
  const digits = (n: number) => n.toLocaleString(intlTag(), { maximumSignificantDigits: size.stated ? STATED_DIGITS : WORKED_OUT_DIGITS });
  const short =
    count >= 1_000_000 ? t("WorkSide.millions", { value: digits(count / 1_000_000) }) : count >= 1000 ? t("WorkSide.thousands", { value: digits(count / 1000) }) : digits(count);
  const shown = t(`WorkSide.${word}`, { count, value: short });
  return size.stated ? (
    <span title={number(count)}>{shown}</span>
  ) : (
    <span title={t("WorkSide.estimate")}>≈ {shown}</span>
  );
}

