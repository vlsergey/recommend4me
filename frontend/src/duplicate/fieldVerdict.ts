import type { AuthorKin, DuplicateValue } from "@/api/client";
import { percent } from "@/i18n/format";

/**
 * HOW ALIKE ONE FIELD OF TWO WORKS IS, as a tone the middle of a pair shows it in: alike (green), partly
 * (yellow), apart (red), or nothing to compare — the field of one work alone (grey). Each with what was
 * measured, shown as it is, and the key of the words saying how it was told.
 */
export type Tone = "alike" | "partly" | "apart" | "none";

export interface FieldVerdict {
  tone: Tone;
  /** What was measured, short: "97 %", "Δ 12 %", "=". */
  shown: string;
  /** The key of the words of the hint, in the namespace "duplicate", and its value. */
  hint:
    | "PairGrid.textHint"
    | "PairGrid.facetHint"
    | "PairGrid.allowedHint"
    | "PairGrid.valuesHint"
    | "PairGrid.sameValueHint"
    | "PairGrid.sameTextHint"
    | "PairGrid.notMeasuredHint"
    | "PairGrid.sizeHint"
    | "PairGrid.sameAuthorHint"
    | "PairGrid.otherAuthorHint"
    | "PairGrid.authorKinHint"
    | "PairGrid.authorKinPartlyHint"
    | "PairGrid.authorKinApartHint"
    | "PairGrid.noAuthorHint"
    | "PairGrid.sameSiteHint"
    | "PairGrid.otherSiteHint"
    | "PairGrid.seriesSiteHint"
    | "PairGrid.sameLineHint"
    | "PairGrid.otherLineHint"
    | "PairGrid.notComparedHint";
  value?: string;
  /** More values of the hint, by their names. */
  values?: Record<string, string | number>;
}

/**
 * The cosine of two texts' vectors that is alike, and partly: of the pairs the user said one work the tenth
 * part has titles of 0.98 and less, descriptions of 0.89; of those said different the middle ones have
 * titles of 0.76, descriptions of 0.58, the tenth of the most alike 0.92 and 0.70 (2026-11-11).
 */
const TEXT_ALIKE = 0.95;
const TEXT_PARTLY = 0.85;

/** A facet's cosine of every value in common, and of none — the cosine of the values weighed by their rarity. */
const FACET_ALL = 0.99;
const FACET_NONE = 0.01;

/** How much two sizes may differ and still be alike, and partly: more than a tenth is apart (the user's word). */
const SIZE_ALIKE = 0.05;
const SIZE_PARTLY = 0.1;

const NOTHING: FieldVerdict = { tone: "none", shown: "—", hint: "PairGrid.notComparedHint" };

/** Two texts — their titles, their descriptions — by the cosine of their vectors; none of a text one has not. */
export function textVerdict(cosine: number | undefined): FieldVerdict {
  if (cosine == null) return NOTHING;
  const tone = cosine >= TEXT_ALIKE ? "alike" : cosine >= TEXT_PARTLY ? "partly" : "apart";
  return { tone, shown: percent(cosine), hint: "PairGrid.textHint", value: percent(cosine) };
}

/** The values of a facet, by their cosine; none when it was not compared — the facet of one work alone. */
export function facetVerdict(cosine: number | undefined): FieldVerdict {
  if (cosine == null) return NOTHING;
  const tone = cosine >= FACET_ALL ? "alike" : cosine <= FACET_NONE ? "apart" : "partly";
  return { tone, shown: percent(cosine), hint: "PairGrid.facetHint", value: percent(cosine) };
}

/**
 * The values each work may have one of — the ratings a site lets it have —: two works agree when their values
 * meet, the same "=", one's within the other's "⊂", some in common "∩"; none in common they differ "≠".
 */
export function allowedVerdict(first: DuplicateValue[], second: DuplicateValue[]): FieldVerdict {
  if (first.length === 0 || second.length === 0) return NOTHING;
  const of = (v: DuplicateValue) => v.nameKey ?? v.name;
  const a = new Set(first.map(of));
  const b = new Set(second.map(of));
  const common = [...a].filter((v) => b.has(v)).length;
  const shown = common === 0 ? "≠" : common === a.size && common === b.size ? "=" : common === a.size || common === b.size ? "⊂" : "∩";
  return { tone: common > 0 ? "alike" : "apart", shown, hint: "PairGrid.allowedHint" };
}

/**
 * OF A PAIR WITH NO MEASURE — its works' texts not encoded yet, the layers of a pair not worked out without them —,
 * its fields compared as they are: the values of a facet by how many they share, all "✓", none "≠", some "k/n"
 * ([valuesVerdict]); a text the same word for word "✓", else not measured ([sameTextVerdict]); the sizes by their
 * numbers ([sizeRatio]).
 */
export function valuesVerdict(first: DuplicateValue[], second: DuplicateValue[]): FieldVerdict {
  if (first.length === 0 || second.length === 0) return NOTHING;
  const of = (v: DuplicateValue) => v.nameKey ?? v.name;
  const a = new Set(first.map(of));
  const b = new Set(second.map(of));
  const common = [...a].filter((v) => b.has(v)).length;
  const all = new Set([...a, ...b]).size;
  const tone = common === all ? "alike" : common === 0 ? "apart" : "partly";
  const shown = tone === "alike" ? "✓" : tone === "apart" ? "≠" : `${common}/${all}`;
  return { tone, shown, hint: "PairGrid.valuesHint", value: `${common}/${all}` };
}

/** A facet of one value at most ([DuplicateWorkFacet.single] — the status, the kind): the same "✓", another "≠". */
export function sameValueVerdict(first: DuplicateValue[], second: DuplicateValue[]): FieldVerdict {
  if (first.length === 0 || second.length === 0) return NOTHING;
  const of = (v: DuplicateValue) => v.nameKey ?? v.name;
  const same = first.some((v) => second.some((w) => of(v) === of(w)));
  return { tone: same ? "alike" : "apart", shown: same ? "✓" : "≠", hint: "PairGrid.sameValueHint" };
}

export function sameTextVerdict(first: string | undefined, second: string | undefined): FieldVerdict {
  if (!first || !second) return NOTHING;
  const same = first.trim().toLocaleLowerCase() === second.trim().toLocaleLowerCase();
  return same ? { tone: "alike", shown: "✓", hint: "PairGrid.sameTextHint" } : { tone: "none", shown: "—", hint: "PairGrid.notMeasuredHint" };
}

/**
 * The site's own line of the characters and pairings: the same word for word "✓"; else not compared itself — the
 * characters and the pairings made of it are, in their rows.
 */
export function lineVerdict(first: string | undefined, second: string | undefined): FieldVerdict {
  if (!first || !second) return NOTHING;
  const same = first.trim().toLocaleLowerCase() === second.trim().toLocaleLowerCase();
  return same ? { tone: "alike", shown: "✓", hint: "PairGrid.sameLineHint" } : { tone: "none", shown: "—", hint: "PairGrid.otherLineHint" };
}

/**
 * The sites of the two: of one work, one site is a doubt — a site seldom has a work twice —, partly; two sites,
 * alike. Of two works asked whether they are of one series ([ofSeries]) the site tells nothing: no colour.
 */
export function siteVerdict(first: string, second: string, ofSeries: boolean): FieldVerdict {
  const same = first === second;
  const shown = same ? "=" : "≠";
  if (ofSeries) return { tone: "none", shown, hint: "PairGrid.seriesSiteHint" };
  return same ? { tone: "partly", shown, hint: "PairGrid.sameSiteHint" } : { tone: "alike", shown, hint: "PairGrid.otherSiteHint" };
}

/** Of two sizes, the smaller over the larger, by the words of both, else by their characters; none of no number of both. */
export function sizeRatio(first: { words?: { value: number }; chars?: { value: number } }, second: typeof first): number | undefined {
  const pair = first.words && second.words ? [first.words.value, second.words.value] : first.chars && second.chars ? [first.chars.value, second.chars.value] : null;
  if (!pair || Math.max(...pair) <= 0) return undefined;
  return Math.min(...pair) / Math.max(...pair);
}

/** The sizes, by the smaller over the larger: how much they differ. */
export function sizeVerdict(ratio: number | undefined): FieldVerdict {
  if (ratio == null) return NOTHING;
  const apart = Math.max(0, 1 - ratio);
  const tone = apart <= SIZE_ALIKE ? "alike" : apart <= SIZE_PARTLY ? "partly" : "apart";
  return { tone, shown: `Δ ${percent(apart)}`, hint: "PairGrid.sizeHint", value: percent(apart) };
}

/** How many works two authors are expected to share by the chances of their pairs before they are counted kin. */
const KIN_EXPECTED = 1;

/**
 * The authors: one by the dictionary of authors, alike. Both told and none in common: of the window of one
 * pair, how close their closest two are by their works ([kin], as the offers of authors weigh it) — the
 * user's word on them first: partly one, partly; told apart, apart; else pairs of their works said one or a
 * part "✓k", or both of one entry, or a work expected to be one by the chances "≈x", partly; nothing of it,
 * apart. The authors of one work unknown, nothing to compare.
 */
export function authorVerdict(sameAuthor: boolean, first: string[], second: string[], kin?: AuthorKin): FieldVerdict {
  if (sameAuthor) return { tone: "alike", shown: "=", hint: "PairGrid.sameAuthorHint" };
  if (first.length === 0 || second.length === 0) return { ...NOTHING, hint: "PairGrid.noAuthorHint" };
  if (!kin) return { tone: "apart", shown: "≠", hint: "PairGrid.otherAuthorHint" };
  const shown = kin.matching > 0 ? `✓${kin.matching}` : kin.expected > 0 ? `≈${kin.expected.toFixed(1)}` : "≠";
  const close = kin.matching > 0 || kin.oneEntry || kin.expected >= KIN_EXPECTED;
  const tone = kin.said === "PARTIAL" ? "partly" : kin.said === "DIFFERENT" ? "apart" : close ? "partly" : "apart";
  const hint = kin.said === "PARTIAL" ? "PairGrid.authorKinPartlyHint" : kin.said === "DIFFERENT" ? "PairGrid.authorKinApartHint" : "PairGrid.authorKinHint";
  return { tone, shown, hint, values: { matching: kin.matching, expected: kin.expected.toFixed(1), entry: kin.oneEntry ? 1 : 0 } };
}

/**
 * The colours of a tone, in the light and the dark skin alike; what differs — partly or apart — in bold, the
 * eye caught by it before what is alike.
 */
export const TONE_CLASS: Record<Tone, string> = {
  alike: "font-medium bg-emerald-500/15 text-emerald-700 dark:text-emerald-300",
  partly: "font-bold bg-amber-500/20 text-amber-800 dark:text-amber-300",
  apart: "font-bold bg-red-500/15 text-red-700 dark:text-red-300",
  none: "font-medium bg-muted text-muted-foreground",
};
