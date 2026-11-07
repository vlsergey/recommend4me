import type { FacetValueInfo } from "@/api/client";

/** The facets the application gives every work of a type with universes (the backend's UniverseFacets). */
export const KIND = "kind";
export const UNIVERSE = "universe";
export const CHARACTERS = "characters";
export const SECONDARY_CHARACTERS = "secondary-characters";
export const PAIRINGS = "pairings";

/** The kinds of a work, by their keys. */
export const FANFICTION = "fanfiction";
export const REALISM = "realism";
export const ALTERNATIVE_HISTORY = "alternative-history";
export const ORIGINAL = "original";

/** The kinds of a work of no universe of another's: our world, ours changed, or a world of its own. */
const UNIVERSELESS: readonly string[] = [REALISM, ALTERNATIVE_HISTORY, ORIGINAL];

/** Known to have no universe: every kind the work has, the user's answers counted, is of no universe. */
export const universeless = (kinds: FacetValueInfo[]) => kinds.length > 0 && kinds.every((k) => UNIVERSELESS.includes(k.key));

/** The original characters, of no universe: every work may have them besides its universes' characters. */
export const ORIGINAL_CHARACTERS = [
  { key: "oc:female", label: "OriginalCharacter.female" },
  { key: "oc:male", label: "OriginalCharacter.male" },
] as const;

/** The work has the value: the site or the model gave it, or the user added it, and the user did not take it away — not one only offered. */
export const has = (v: FacetValueInfo) => v.corrected !== "REMOVED" && !v.offered;

/** A search of a field opened by a site's word or by its own button; every press is a new search, the same word pressed again too. */
export type FieldSearch = { facet: string; word: string; press: number };
