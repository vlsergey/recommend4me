import { type ReactNode, useState } from "react";
import { ExternalLinkIcon, LibraryBigIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { DuplicatePair, DuplicateSort, DuplicateWork, DuplicateWorkFacet, SizeNumber } from "@/api/client";
import { number, percent } from "@/i18n/format";
import { intlTag } from "@/i18n/i18n";
import { label, sourceShort, sourceTitle, valueName } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { pressKeys } from "@/lib/pressKeys";
import { cn } from "@/lib/utils";
import { allowedVerdict, authorVerdict, type FieldVerdict, facetVerdict, sameTextVerdict, sizeRatio, sizeVerdict, TONE_CLASS, textVerdict, valuesVerdict } from "./fieldVerdict";

/**
 * TWO WORKS COMPARED, as the screen of duplicates shows a pair and every other place that asks whether two
 * works are one: what is of the pair as a whole ([PairFacts]) and the two field by field ([PairGrid]).
 */

/**
 * What is of the pair as a whole: the chance they are one work and one series, the [priority] of the
 * list's sort when it says something of the work. How alike each field is stands by the field ([PairGrid]).
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
      {priority && p.priority != null && p.priority > 0 && (
        // Of the sort by size the priority is words: a whole number, not a score of two digits
        <span className="tabular-nums" title={t(priority === "SIZE" ? "PairRow.prioritySizeHint" : "PairRow.priorityHint")}>
          {t("PairRow.priority", { value: priority === "SIZE" ? number(Math.round(p.priority)) : p.priority.toFixed(2) })}
        </span>
      )}
    </>
  );
}

/** The facets compared in the order the user decides by: what the work is of first, the tags last; any other after them. */
const FACET_ORDER = ["universe", "characters", "pairings", "kind", "status", "languages", "direction", "age-rating", "age-rating-site", "tags"];

/** The facet of the universes: of a work with none, the site's fandoms stand in it. */
const UNIVERSE = "universe";

/** The normalized authors, compared apart: the row of the authors tells them. */
const AUTHOR = "author";

/** A row of the grid: its field, how alike the two are of it, the two values, folded or not. */
interface Row {
  key: string;
  name: string;
  verdict: FieldVerdict;
  left: ReactNode;
  right: ReactNode;
  /** Long values folded to these lines ([FoldedRow]); none, shown whole. */
  lines?: string;
}

/**
 * THE TWO WORKS FIELD BY FIELD: a row a field — the title, the authors, every facet either has, the size,
 * the description — the first work's on the left, the second's on the right, and between them the field's
 * name and how alike the two are, in its colour ([FieldVerdict]), on one line: read across, the left, the
 * verdict, the right. The fields of one work alone — nothing to compare — stand below the rest, under a line
 * of their own: told, not in the way of what is compared. On a narrow screen the name and the verdict stand
 * over the row, the two under them side by side.
 */
export function PairGrid({ pair: p }: { pair: DuplicatePair }) {
  const { t } = useTranslation("duplicate");
  const a = p.first;
  const b = p.second;
  const rows: Row[] = [
    {
      key: "title",
      name: t("PairGrid.title"),
      // Of a pair the search did not compare, its fields as they are ([valuesVerdict])
      verdict: p.title != null ? textVerdict(p.title) : sameTextVerdict(a.title, b.title),
      left: <WorkHead work={a} />,
      right: <WorkHead work={b} />,
    },
    {
      key: "authors",
      name: t("PairGrid.authors"),
      verdict: authorVerdict(p.sameAuthor, a.authors, b.authors),
      left: a.authors.length > 0 ? a.authors.join(", ") : null,
      right: b.authors.length > 0 ? b.authors.join(", ") : null,
    },
    ...facetKeys(p).map((key): Row => {
      const left = a.facets.find((f) => f.key === key);
      const right = b.facets.find((f) => f.key === key);
      const compared = p.facets?.find((f) => f.key === key);
      const labelKey = left?.labelKey ?? right?.labelKey ?? compared?.labelKey;
      return {
        key,
        // Of the universes of no work, the site's fandoms alone: named as they are
        name: labelKey ? label(labelKey) : key === UNIVERSE ? t("PairGrid.fandoms") : key,
        // Of the values a work may have one of, whether they meet; of any other, how alike as the search compared them
        verdict:
          left?.allowed || right?.allowed
            ? allowedVerdict(left?.values ?? [], right?.values ?? [])
            : compared
              ? facetVerdict(compared.value)
              : valuesVerdict(left?.values ?? [], right?.values ?? []),
        left: valuesOf(left, key === UNIVERSE ? a.fandoms : []),
        right: valuesOf(right, key === UNIVERSE ? b.fandoms : []),
        lines: "line-clamp-2",
      };
    }),
    {
      key: "size",
      name: t("PairGrid.size"),
      verdict: sizeVerdict(p.size ?? sizeRatio(a, b)),
      left: sized(a) ? <Sizes work={a} /> : null,
      right: sized(b) ? <Sizes work={b} /> : null,
    },
    {
      key: "description",
      name: t("PairGrid.description"),
      verdict: p.description != null ? textVerdict(p.description) : sameTextVerdict(a.description, b.description),
      left: described(a),
      right: described(b),
      lines: "line-clamp-4",
    },
  ];
  const both = rows.filter((r) => r.left != null && r.right != null);
  const one = rows.filter((r) => r.left == null || r.right == null);
  return (
    <div className="flex flex-col divide-y rounded-md border">
      {both.map(row)}
      {one.length > 0 && <div className="bg-muted/40 px-2 py-0.5 text-xs text-muted-foreground">{t("PairGrid.oneSided")}</div>}
      {one.map(row)}
    </div>
  );
}

/** A row drawn: folded when its values are long. */
const row = (r: Row) =>
  r.lines ? <FoldedRow key={r.key} name={r.name} verdict={r.verdict} left={r.left} right={r.right} lines={r.lines} /> : <FieldRow key={r.key} name={r.name} verdict={r.verdict} left={r.left} right={r.right} />;

/**
 * The facets of the rows: every one either work has or the pair was compared by — the universes when either
 * has its site's fandoms too —, in [FACET_ORDER], any other after them; the authors apart.
 */
function facetKeys(p: DuplicatePair): string[] {
  const all = new Set([...p.first.facets.map((f) => f.key), ...p.second.facets.map((f) => f.key), ...(p.facets ?? []).map((f) => f.key)]);
  if (p.first.fandoms.length > 0 || p.second.fandoms.length > 0) all.add(UNIVERSE);
  all.delete(AUTHOR);
  const known = FACET_ORDER.filter((k) => all.has(k));
  return [...known, ...[...all].filter((k) => !FACET_ORDER.includes(k))];
}

/** A work's values of a facet, by their names; of none, the site's own names of it [site] — its fandoms — told apart. */
function valuesOf(facet: DuplicateWorkFacet | undefined, site: string[]): ReactNode {
  if (facet && facet.values.length > 0) return facet.values.map(valueName).join(", ");
  return site.length > 0 ? <SiteNames names={site} /> : null;
}

/** The site's own names of what the work is of, where the application knows no universe of it. */
function SiteNames({ names }: { names: string[] }) {
  const { t } = useTranslation("duplicate");
  return (
    <span className="italic" title={t("PairGrid.siteFandomsHint")}>
      {names.join(", ")}
    </span>
  );
}

/** A work's description, the site's own line of its characters and pairings after it; none of a work with neither. */
function described(w: DuplicateWork): ReactNode {
  if (!w.description && !w.universeLine) return null;
  return (
    <>
      {w.description}
      {w.description && w.universeLine && "\n"}
      {w.universeLine && <span className="text-foreground/80">{w.universeLine}</span>}
    </>
  );
}

/**
 * A row of a field: the first work's on the left, the name and the verdict in the middle, the second's on
 * the right; a field one work has not, a dash on its side.
 */
function FieldRow({ name, verdict, left, right }: { name: string; verdict: FieldVerdict; left: ReactNode; right: ReactNode }) {
  return (
    <div className="grid grid-cols-2 gap-x-3 gap-y-0.5 px-2 py-1 md:grid-cols-[minmax(0,1fr)_12rem_minmax(0,1fr)]">
      <Side>{left}</Side>
      <Middle name={name} verdict={verdict} />
      <Side>{right}</Side>
    </div>
  );
}

/**
 * A row of a field of long values — a description, the tags — folded to a few [lines], unfolded by a press
 * on either side for both: what is compared is read whole side by side. Text to select and copy: a press
 * that selects something does not fold.
 */
function FoldedRow({ name, verdict, left, right, lines }: { name: string; verdict: FieldVerdict; left: ReactNode; right: ReactNode; lines: string }) {
  const { t } = useTranslation("duplicate");
  const [whole, setWhole] = useState(false);
  const side = (content: ReactNode) =>
    content == null ? null : (
      // biome-ignore lint/a11y/useSemanticElements: text to select and copy, which a button's text cannot be
      <div
        role="button"
        tabIndex={0}
        className={cn("cursor-pointer whitespace-pre-line select-text", !whole && lines)}
        title={t(whole ? "WorkSide.fold" : "WorkSide.unfold")}
        onClick={() => {
          if (!window.getSelection()?.toString()) setWhole(!whole);
        }}
        onKeyDown={pressKeys(() => setWhole(!whole))}
      >
        {content}
      </div>
    );
  return <FieldRow name={name} verdict={verdict} left={side(left)} right={side(right)} />;
}

/** One work's side of a row: its value, or a dash of a field it has not. */
function Side({ children }: { children: ReactNode }) {
  return <div className="min-w-0 text-sm text-muted-foreground">{children ?? <span className="text-muted-foreground/60">—</span>}</div>;
}

/** The middle of a row: the field's name and how alike the two works are of it, in its colour, the hint telling how. */
function Middle({ name, verdict }: { name: string; verdict: FieldVerdict }) {
  const { t } = useTranslation("duplicate");
  return (
    <div className="col-span-2 order-first flex items-baseline gap-2 md:order-none md:col-span-1 md:justify-center">
      <span className="min-w-0 truncate text-xs text-muted-foreground" title={name}>
        {name}
      </span>
      <span className={cn("shrink-0 rounded px-1.5 text-xs tabular-nums", TONE_CLASS[verdict.tone])} title={t(verdict.hint, { value: verdict.value })}>
        {verdict.shown}
      </span>
    </div>
  );
}

/** A work's head: its site, its title opening the work, its page on the site. */
function WorkHead({ work: w }: { work: DuplicateWork }) {
  const { t } = useTranslation("duplicate");
  return (
    <div className="flex items-baseline gap-2">
      <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
        {sourceShort(w.source)}
      </span>
      <a href={itemHref({ source: w.source, item: w.item })} className="min-w-0 font-semibold text-foreground underline-offset-2 hover:underline">
        {w.title}
      </a>
      <a href={w.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("WorkSide.page")}>
        <ExternalLinkIcon className="size-3.5" />
      </a>
    </div>
  );
}

/** Whether the size of a work is known: its words or its characters. */
const sized = (w: DuplicateWork) => w.words != null || w.chars != null;

/** A work's size: its words and its characters, as known ([sized]). */
function Sizes({ work: w }: { work: DuplicateWork }) {
  return (
    <>
      {w.words && <Size size={w.words} word="words" />}
      {w.words && w.chars && "; "}
      {w.chars && <Size size={w.chars} word="chars" />}
    </>
  );
}

/** How many significant digits a size keeps: the site's number three, one worked out two — it is known no better. */
const STATED_DIGITS = 3;
const WORKED_OUT_DIGITS = 2;

/** How long a work is, short, in thousands or millions: a number worked out after a "≈". */
export function Size({ size, word }: { size: SizeNumber; word: "words" | "chars" }) {
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
