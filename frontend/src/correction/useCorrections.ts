import { useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ItemDetails, type ItemRef } from "@/api/client";
import { applyDetails, ITEMS, removeFromLists } from "@/item/lists";

/** The fields a correction overrides: the title, a text, a number. */
export const titleField = "title";
export const textField = (key: string) => `text:${key}`;
export const numberField = (key: string) => `number:${key}`;

/**
 * The user's corrections of one item. Each returns once the item is saved and its new details
 * are in the dialog and in the lists; a failure throws, for the button that asked to show it.
 */
export function useCorrections(item: ItemRef, typeId: string) {
  const queryClient = useQueryClient();
  const path = { source: item.source, item: item.item };

  const done = (details: ItemDetails) => {
    applyDetails(queryClient, details, item);
    // The counts of the filter values change with the values of the item
    queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
    return details;
  };

  return {
    setField: async (field: string, content: string) =>
      done(unwrap(await api.PUT("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } }, body: { content } }))),

    resetField: async (field: string) =>
      done(unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } } }))),

    /** Adds a value of a facet ([added]) or takes away one the site gives. */
    setFacet: async (facet: string, key: string, added: boolean, name?: string) =>
      done(unwrap(await api.PUT("/api/items/{source}/{item}/corrections/facets", { params: { path }, body: { facet, key, added, name } }))),

    resetFacet: async (facet: string, key: string) =>
      done(unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/facets", { params: { path, query: { facet, key } } }))),

    /** The other item becomes a part of this card: it leaves the lists at once. */
    link: async (other: ItemRef) => {
      const result = await api.POST("/api/items/{source}/{item}/links", { params: { path }, body: { source: other.source, item: other.item } });
      if (result.response.status === 400) throw new Error("это работа другого типа или неизвестная");
      const details = done(unwrap(result));
      removeFromLists(queryClient, other);
      return details;
    },

    /** The other item gets a card of its own again: the lists are asked for anew. */
    unlink: async (other: ItemRef) => {
      const details = done(
        unwrap(
          await api.DELETE("/api/items/{source}/{item}/links", { params: { path, query: { otherSource: other.source, otherItem: other.item } } }),
        ),
      );
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      return details;
    },
  };
}

export type Corrections = ReturnType<typeof useCorrections>;
