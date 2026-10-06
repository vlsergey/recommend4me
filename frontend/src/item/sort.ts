import type { ContentTypeInfo, ItemSort } from "@/api/client";

/** The order of the list: by prediction, by update, or by one of the numbers of the content type. */
export type SortChoice = { sort: ItemSort; number?: string };

export const BY_SCORE: SortChoice = { sort: "SCORE" };

/** A remembered order whose number the content type no longer has falls back to the prediction. */
export function validSort(choice: SortChoice, type: ContentTypeInfo): SortChoice {
  if (choice.sort !== "NUMBER") return choice;
  return type.numbers.some((n) => n.id === choice.number) ? choice : BY_SCORE;
}
