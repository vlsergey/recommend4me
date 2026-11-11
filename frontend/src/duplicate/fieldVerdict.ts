import type { DuplicateValue } from "@/api/client";
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
    | "PairGrid.allowedHint"
    | "PairGrid.valuesHint"
    | "PairGrid.sameTextHint"
    | "PairGrid.notMeasuredHint"
    | "PairGrid.sizeHint"
    | "PairGrid.sameAuthorHint"
    | "PairGrid.otherAuthorHint"
    | "PairGrid.noAuthorHint"
    | "PairGrid.notComparedHint";
  value?: string;
}

/**
 * The cosine of two texts' vectors that is alike, and partly: of the pairs the user said one work the tenth
 * part has titles of 0.98 and less, descriptions of 0.89; of those said different the middle ones have
 * titles of 0.76, descriptions of 0.58, the tenth of the most alike 0.92 and 0.70 (2026-11-11).
 */
const TEXT_ALIKE = 0.95;
const TEXT_PARTLY = 0.85;

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
 * The values of a facet by how many they share: all "✓", none "≠", some "k/n" ([valuesVerdict]). Two texts
 * not encoded yet — no vector to tell how alike they mean — the same word for word "✓", else not measured
 * ([sameTextVerdict]). The sizes by their numbers ([sizeRatio]).
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

export function sameTextVerdict(first: string | undefined, second: string | undefined): FieldVerdict {
  if (!first || !second) return NOTHING;
  const same = first.trim().toLocaleLowerCase() === second.trim().toLocaleLowerCase();
  return same ? { tone: "alike", shown: "✓", hint: "PairGrid.sameTextHint" } : { tone: "none", shown: "—", hint: "PairGrid.notMeasuredHint" };
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

/**
 * The authors: one by the dictionary of authors, alike; both told and none in common, most likely apart;
 * the authors of one work unknown, nothing to compare.
 */
export function authorVerdict(sameAuthor: boolean, first: string[], second: string[]): FieldVerdict {
  if (sameAuthor) return { tone: "alike", shown: "=", hint: "PairGrid.sameAuthorHint" };
  if (first.length > 0 && second.length > 0) return { tone: "apart", shown: "≠", hint: "PairGrid.otherAuthorHint" };
  return { ...NOTHING, hint: "PairGrid.noAuthorHint" };
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
