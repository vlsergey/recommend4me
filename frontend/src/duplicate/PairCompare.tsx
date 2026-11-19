import { type ReactNode, useState } from "react";
import { ExternalLinkIcon, LibraryBigIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { DuplicatePair, DuplicateSort, DuplicateWork, PairCell, PairRow, SizeNumber } from "@/api/client";
import { number, percent } from "@/i18n/format";
import { intlTag } from "@/i18n/i18n";
import { label, sourceShort, sourceTitle, valueName } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { pressKeys } from "@/lib/pressKeys";
import { cn } from "@/lib/utils";
import { type FieldVerdict, measureVerdict, TONE_CLASS } from "./fieldVerdict";

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

/** The lines a long value is folded to, by the server's number of them: classes written whole, for Tailwind. */
const CLAMP: Record<number, string> = { 2: "line-clamp-2", 3: "line-clamp-3", 4: "line-clamp-4" };

/**
 * THE TWO WORKS FIELD BY FIELD, as the server lays them out ([PairRow], owner, 2026-11-20: the window driven by the
 * server — a layer the search compares never shown "not compared"): a row a field, the first work's on the left, the
 * second's on the right, and between them the field's name and how alike the two are by every measure the search
 * takes of it, each in its colour ([measureVerdict]), on one line: read across, the left, the verdict, the right. In
 * three groups (user, 2026-11-18: "what both works have but is not compared goes to a group below"): what the pair is
 * compared by; what both works have but the search compares not, no verdict; the fields of one work alone. On a
 * narrow screen the name and the verdict stand over the row, the two under them side by side. Of a pair asked whether
 * it is of one series ([ofSeries]), the site says nothing: no colour of it.
 */
export function PairGrid({ pair: p, ofSeries = false }: { pair: DuplicatePair; ofSeries?: boolean }) {
  const { t } = useTranslation("duplicate");
  const of = (group: PairRow["group"]) => p.rows.filter((r) => r.group === group);
  const notCompared = of("NOT_COMPARED");
  const one = of("ONE_SIDED");
  const row = (r: PairRow) => <Row key={r.key} row={r} pair={p} ofSeries={ofSeries} />;
  return (
    <div className="flex flex-col divide-y rounded-md border">
      {of("COMPARED").map(row)}
      {notCompared.length > 0 && <GroupHead title={t("PairGrid.notComparedHint2")}>{t("PairGrid.notCompared")}</GroupHead>}
      {notCompared.map(row)}
      {one.length > 0 && <GroupHead>{t("PairGrid.oneSided")}</GroupHead>}
      {one.map(row)}
    </div>
  );
}

/** The line a group of rows begins with: what its rows are. */
function GroupHead({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="bg-muted/40 px-2 py-0.5 text-xs text-muted-foreground" title={title}>
      {children}
    </div>
  );
}

/** A row drawn: its name, its measures, its two cells — folded when its values are long. */
function Row({ row: r, pair: p, ofSeries }: { row: PairRow; pair: DuplicatePair; ofSeries: boolean }) {
  const name = label(r.labelKey);
  const verdicts = r.measures.map((m) => measureVerdict(m, ofSeries));
  const left = cellOf(r, p.first, r.first);
  const right = cellOf(r, p.second, r.second);
  const lines = r.folded != null ? CLAMP[r.folded] : undefined;
  return lines ? <FoldedRow name={name} verdicts={verdicts} left={left} right={right} lines={lines} /> : <FieldRow name={name} verdicts={verdicts} left={left} right={right} />;
}

/** A work's cell of a row as the row says it is drawn; none of a work without the field. */
function cellOf(r: PairRow, w: DuplicateWork, cell: PairCell | undefined): ReactNode {
  if (!cell) return null;
  switch (r.show) {
    case "SITE":
      return <span title={sourceTitle(w.source)}>{sourceShort(w.source)}</span>;
    case "TITLE":
      return <WorkHead work={w} />;
    case "VALUES":
      return (cell.values ?? []).map(valueName).join(", ");
    case "TEXT":
      return cell.text;
    case "SIZE":
      return <Sizes cell={cell} />;
  }
}

/**
 * A row of a field: the first work's on the left, the name and the verdicts in the middle, the second's on
 * the right; a field one work has not, a dash on its side.
 */
function FieldRow({ name, verdicts, left, right }: { name: string; verdicts: FieldVerdict[]; left: ReactNode; right: ReactNode }) {
  return (
    <div className="grid grid-cols-2 gap-x-3 gap-y-0.5 px-2 py-1 md:grid-cols-[minmax(0,1fr)_12rem_minmax(0,1fr)]">
      <Side>{left}</Side>
      <Middle name={name} verdicts={verdicts} />
      <Side>{right}</Side>
    </div>
  );
}

/**
 * A row of a field of long values — a description, the tags — folded to a few [lines], unfolded by a press
 * on either side for both: what is compared is read whole side by side. Text to select and copy: a press
 * that selects something does not fold.
 */
function FoldedRow({ name, verdicts, left, right, lines }: { name: string; verdicts: FieldVerdict[]; left: ReactNode; right: ReactNode; lines: string }) {
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
  return <FieldRow name={name} verdicts={verdicts} left={side(left)} right={side(right)} />;
}

/** One work's side of a row: its value, or a dash of a field it has not. */
function Side({ children }: { children: ReactNode }) {
  return <div className="min-w-0 text-sm text-muted-foreground">{children ?? <span className="text-muted-foreground/60">—</span>}</div>;
}

/**
 * The middle of a row: the field's name and how alike the two works are of it by every measure — of a text letter by
 * letter, then by meaning; of a field the pair is not compared by, its name alone.
 */
function Middle({ name, verdicts }: { name: string; verdicts: FieldVerdict[] }) {
  return (
    <div className="col-span-2 order-first flex items-baseline gap-2 md:order-none md:col-span-1 md:justify-center">
      <span className="min-w-0 truncate text-xs text-muted-foreground" title={name}>
        {name}
      </span>
      {verdicts.map((v) => (
        <Measure key={v.hint} verdict={v} />
      ))}
    </div>
  );
}

/** How alike the two works are by one measure, in its colour, the hint telling how. */
function Measure({ verdict }: { verdict: FieldVerdict }) {
  const { t } = useTranslation("duplicate");
  return (
    <span className={cn("shrink-0 rounded px-1.5 text-xs tabular-nums", TONE_CLASS[verdict.tone])} title={t(verdict.hint, { value: verdict.value, ...verdict.values })}>
      {verdict.shown}
    </span>
  );
}

/** A work's head: its title opening the work, its page on the site; the site a row of its own. */
function WorkHead({ work: w }: { work: DuplicateWork }) {
  const { t } = useTranslation("duplicate");
  return (
    <div className="flex items-baseline gap-2">
      <a href={itemHref({ source: w.source, item: w.item })} className="min-w-0 font-semibold text-foreground underline-offset-2 hover:underline">
        {w.title}
      </a>
      <a href={w.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("WorkSide.page")}>
        <ExternalLinkIcon className="size-3.5" />
      </a>
    </div>
  );
}

/** A work's size: its words and its characters, as known. */
function Sizes({ cell: c }: { cell: PairCell }) {
  return (
    <>
      {c.words && <Size size={c.words} word="words" />}
      {c.words && c.chars && "; "}
      {c.chars && <Size size={c.chars} word="chars" />}
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
