import type { AuthorKin, PairMeasure } from "@/api/client";
import { percent } from "@/i18n/format";

/**
 * HOW ALIKE ONE FIELD OF TWO WORKS IS BY ONE MEASURE, as a tone the middle of a pair shows it in: alike (green),
 * partly (yellow), apart (red), or not measured (grey). Each with what was measured, shown as it is, and the key of
 * the words saying how it was told. What a field is measured by is the server's ([PairMeasure], owner, 2026-11-20:
 * the window driven by the server): here only how a measure looks.
 */
export type Tone = "alike" | "partly" | "apart" | "none";

export interface FieldVerdict {
  tone: Tone;
  /** What was measured, short: "97 %", "Δ 12 %", "=". */
  shown: string;
  /** The key of the words of the hint, in the namespace "duplicate", and its value. */
  hint:
    | "PairGrid.textHint"
    | "PairGrid.lettersHint"
    | "PairGrid.partlyAuthorHint"
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
    | "PairGrid.sameSiteHint"
    | "PairGrid.otherSiteHint"
    | "PairGrid.seriesSiteHint";
  value?: string;
  /** More values of the hint, by their names. */
  values?: Record<string, string | number>;
}

/**
 * The cosine of two texts that is alike, and partly: of the pairs the user said one work the tenth part has titles
 * of 0.98 and less, descriptions of 0.89; of those said different the middle ones have titles of 0.76, descriptions
 * of 0.58, the tenth of the most alike 0.92 and 0.70 (2026-11-11).
 */
const TEXT_ALIKE = 0.95;
const TEXT_PARTLY = 0.85;

/** A facet's cosine of every value in common, and of none — the cosine of the values weighed by their rarity. */
const FACET_ALL = 0.99;
const FACET_NONE = 0.01;

/** How much two sizes may differ and still be alike, and partly: more than a tenth is apart (the user's word). */
const SIZE_ALIKE = 0.05;
const SIZE_PARTLY = 0.1;

/** How many works two authors are expected to share by the chances of their pairs before they are counted kin. */
const KIN_EXPECTED = 1;

/** A cosine of two texts — letter by letter, by meaning — told by [hint]. */
function cosine(value: number | undefined, hint: "PairGrid.lettersHint" | "PairGrid.textHint"): FieldVerdict {
  const c = value ?? 0;
  const tone = c >= TEXT_ALIKE ? "alike" : c >= TEXT_PARTLY ? "partly" : "apart";
  return { tone, shown: percent(c), hint, value: percent(c) };
}

/**
 * The values each work may have one of — the ratings a site lets it have —: two works agree when their values meet,
 * the same "=", one's within the other's "⊂", some in common "∩"; none in common they differ "≠".
 */
function allowed({ common = 0, first = 0, second = 0 }: PairMeasure): FieldVerdict {
  const shown = common === 0 ? "≠" : common === first && common === second ? "=" : common === first || common === second ? "⊂" : "∩";
  return { tone: common > 0 ? "alike" : "apart", shown, hint: "PairGrid.allowedHint" };
}

/** The values of two works by how many they share: all "✓", none "≠", some "k/n". */
function shared({ common = 0, first = 0, second = 0 }: PairMeasure): FieldVerdict {
  const all = first + second - common;
  const tone = common === all ? "alike" : common === 0 ? "apart" : "partly";
  const shown = tone === "alike" ? "✓" : tone === "apart" ? "≠" : `${common}/${all}`;
  return { tone, shown, hint: "PairGrid.valuesHint", value: `${common}/${all}` };
}

/** The sizes, by the smaller over the larger: how much they differ. */
function size(ratio = 0): FieldVerdict {
  const apart = Math.max(0, 1 - ratio);
  const tone = apart <= SIZE_ALIKE ? "alike" : apart <= SIZE_PARTLY ? "partly" : "apart";
  return { tone, shown: `Δ ${percent(apart)}`, hint: "PairGrid.sizeHint", value: percent(apart) };
}

/**
 * Two authors of no author in common, of the window of one pair: how close their closest two are by their works —
 * the user's word on them first: partly one, partly; told apart, apart; else pairs of their works said one or a part
 * "✓k", or both of one entry, or a work expected to be one by the chances "≈x", partly; nothing of it, apart.
 */
function kinVerdict(kin: AuthorKin | undefined): FieldVerdict {
  if (!kin) return { tone: "apart", shown: "≠", hint: "PairGrid.otherAuthorHint" };
  const shown = kin.matching > 0 ? `✓${kin.matching}` : kin.expected > 0 ? `≈${kin.expected.toFixed(1)}` : "≠";
  const close = kin.matching > 0 || kin.oneEntry || kin.expected >= KIN_EXPECTED;
  const tone = kin.said === "PARTIAL" ? "partly" : kin.said === "DIFFERENT" ? "apart" : close ? "partly" : "apart";
  const hint = kin.said === "PARTIAL" ? "PairGrid.authorKinPartlyHint" : kin.said === "DIFFERENT" ? "PairGrid.authorKinApartHint" : "PairGrid.authorKinHint";
  return { tone, shown, hint, values: { matching: kin.matching, expected: kin.expected.toFixed(1), entry: kin.oneEntry ? 1 : 0 } };
}

/**
 * How every kind of measure looks: a kind the server adds fails the build here till it is drawn. The sites of the
 * two: of one work, one site is a doubt — a site seldom has a work twice —, partly; two sites, alike; of two works
 * asked whether they are of one series ([ofSeries]), the site tells nothing: no colour.
 */
const VERDICTS: Record<PairMeasure["kind"], (m: PairMeasure, ofSeries: boolean) => FieldVerdict> = {
  LETTERS: (m) => cosine(m.value, "PairGrid.lettersHint"),
  MEANING: (m) => cosine(m.value, "PairGrid.textHint"),
  FACET: ({ value = 0 }) => ({
    tone: value >= FACET_ALL ? "alike" : value <= FACET_NONE ? "apart" : "partly",
    shown: percent(value),
    hint: "PairGrid.facetHint",
    value: percent(value),
  }),
  NOT_MEASURED: () => ({ tone: "none", shown: "—", hint: "PairGrid.notMeasuredHint" }),
  ALLOWED: allowed,
  VALUES: shared,
  SAME_VALUE: () => ({ tone: "alike", shown: "✓", hint: "PairGrid.sameValueHint" }),
  OTHER_VALUE: () => ({ tone: "apart", shown: "≠", hint: "PairGrid.sameValueHint" }),
  SAME_TEXT: () => ({ tone: "alike", shown: "✓", hint: "PairGrid.sameTextHint" }),
  SIZE: (m) => size(m.value),
  SAME_AUTHOR: () => ({ tone: "alike", shown: "=", hint: "PairGrid.sameAuthorHint" }),
  PARTLY_AUTHOR: () => ({ tone: "alike", shown: "≈", hint: "PairGrid.partlyAuthorHint" }),
  OTHER_AUTHOR: () => ({ tone: "apart", shown: "≠", hint: "PairGrid.otherAuthorHint" }),
  AUTHOR_KIN: (m) => kinVerdict(m.kin),
  SAME_SITE: (_, ofSeries) => (ofSeries ? { tone: "none", shown: "=", hint: "PairGrid.seriesSiteHint" } : { tone: "partly", shown: "=", hint: "PairGrid.sameSiteHint" }),
  OTHER_SITE: (_, ofSeries) => (ofSeries ? { tone: "none", shown: "≠", hint: "PairGrid.seriesSiteHint" } : { tone: "alike", shown: "≠", hint: "PairGrid.otherSiteHint" }),
};

/** How a measure of a field looks ([VERDICTS]). */
export function measureVerdict(m: PairMeasure, ofSeries: boolean): FieldVerdict {
  return VERDICTS[m.kind](m, ofSeries);
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
