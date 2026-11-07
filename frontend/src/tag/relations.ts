import type { TagRelation } from "@/api/client";

/** Above or under this one through other tags: read, never said of the two — said of the tags between. */
export type Through = "DESCENDANT" | "ANCESTOR";
export const THROUGH: Through[] = ["DESCENDANT", "ANCESTOR"];

/** What the user may say another tag is to this one. */
export type Said = Exclude<TagRelation, "NONE" | Through>;

/**
 * What the user may say another tag is to this one: a button of every row, and a filter of the rows
 * said so; the words of each are the keys "Relation.<relation>.label", ".title" and ".filter".
 */
export const RELATIONS: Said[] = ["MERGED", "CHILD", "PARENT", "DIFFERENT"];
