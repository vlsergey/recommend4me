import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

export const api = createClient<paths>({ baseUrl: "" });

type Schemas = components["schemas"];
export type ContentTypeInfo = Schemas["ContentTypeInfo"];
export type SourceInfo = Schemas["SourceInfo"];
export type SourceMode = Schemas["SourceMode"];
export type FacetInfo = Schemas["FacetInfo"];
export type NumberInfo = Schemas["NumberInfo"];
export type ItemView = Schemas["ItemView"];
export type ItemSort = Schemas["ItemSort"];
export type ItemFacet = Schemas["ItemFacet"];
export type FacetValueInfo = Schemas["FacetValueInfo"];
export type ItemSummary = Schemas["ItemSummary"];
export type ItemDetails = Schemas["ItemDetails"];
export type ItemPage = Schemas["ItemPage"];
export type ItemRef = Schemas["ItemRef"];
export type LinkedItem = Schemas["LinkedItem"];
export type SearchMatch = Schemas["SearchMatch"];
export type FacetFilter = Schemas["FacetFilter"];
export type TextValue = Schemas["TextValue"];
export type NumberValue = Schemas["NumberValue"];
export type PartInfo = Schemas["PartInfo"];
export type PictureInfo = Schemas["PictureInfo"];
export type PictureMatch = Schemas["PictureMatch"];
export type ReviewInfo = Schemas["ReviewInfo"];
export type ReviewMatch = Schemas["ReviewMatch"];
export type FeatureContribution = Schemas["FeatureContribution"];
export type Grade = Schemas["Grade"];
export type JobStatus = Schemas["JobStatus"];
export type ModelInfo = Schemas["ModelInfo"];
export type ModelCandidate = Schemas["ModelCandidate"];
export type ScorerQuality = Schemas["ScorerQuality"];
export type GradeOnScale = Schemas["GradeOnScale"];
export type SourceSettings = Schemas["SourceSettings"];
export type SettingValue = Schemas["SettingValue"];
export type WorkStatus = Schemas["WorkStatus"];
export type CapturedPageInfo = Schemas["CapturedPageInfo"];

/** Returns the body of a successful response or throws with the HTTP status. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined) {
    throw new Error(`HTTP ${result.response.status}`);
  }
  return result.data;
}

/**
 * Throws with the HTTP status unless the response is a success: for the calls that return
 * nothing, which openapi-fetch reports as an `error` field rather than by throwing.
 */
export function ensureOk(result: { error?: unknown; response: Response }): void {
  if (!result.response.ok) throw new Error(`HTTP ${result.response.status}`);
}

/** The key of an item across sources: one card, one cache entry. */
export function refKey(ref: { source: string; item: string }): string {
  return `${ref.source}/${ref.item}`;
}

export function sameItem(a: { source: string; item: string }, b: { source: string; item: string }): boolean {
  return a.source === b.source && a.item === b.item;
}

/**
 * A picture of the item: 0 the cover, 1.. the screenshots. The preview is kept locally; the
 * original may not be — the backend then sends the browser to the site for it.
 */
export function pictureSrc(ref: { source: string; item: string }, position: number, full = false): string {
  return `/api/items/${encodeURIComponent(ref.source)}/${encodeURIComponent(ref.item)}/pictures/${position}${full ? "?full=true" : ""}`;
}
