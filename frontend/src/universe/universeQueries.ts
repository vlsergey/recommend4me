import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ensureOk, unwrap, type CharacterInfo, type UniverseClassInfo, type UniverseEntryInfo, type UniverseInfo } from "@/api/client";
import { ITEMS } from "@/item/lists";

/** The cache key of the universes: ["universes", type]; an entry's characters and classes lie under it. */
export const UNIVERSES = "universes";

/** An entry of a catalogue a universe is bound to, as the paths of its characters and classes name it. */
export type EntryRef = { catalogue: string; universe: string };

/** The entry as the paths of its characters and classes name it. */
export const entryRef = (e: UniverseEntryInfo): EntryRef => ({ catalogue: e.catalogue, universe: e.entry });

/** The universes — the normalized fandoms — of a content type. */
export function useUniverses(typeId: string, enabled = true) {
  return useQuery({
    queryKey: [UNIVERSES, typeId],
    enabled,
    queryFn: async () => unwrap(await api.GET("/api/universes", {})),
  });
}

/** The entries whose characters a work of the universe may have: every entry of it when its characters are read, else none. */
export const characterEntries = (u: UniverseInfo | undefined): UniverseEntryInfo[] => (u?.characters ? u.entries : []);

/** The query of the characters of an entry of the dictionary, as the catalogue gave them. */
export function charactersQuery(typeId: string, e: EntryRef) {
  return {
    queryKey: [UNIVERSES, typeId, e.catalogue, e.universe, "characters"],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/universes/{catalogue}/{universe}/characters", {
          params: { path: { catalogue: e.catalogue, universe: e.universe } },
        }),
      ),
  };
}

/** The query of the classes the entries of an entry are of, the most entries first. */
export function classesQuery(typeId: string, e: EntryRef) {
  return {
    queryKey: [UNIVERSES, typeId, e.catalogue, e.universe, "classes"],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/universes/{catalogue}/{universe}/classes", {
          params: { path: { catalogue: e.catalogue, universe: e.universe } },
        }),
      ),
  };
}

/**
 * What to ask again after the universes changed. The universes are the values of the facet
 * "universe", so its filter and its offers change with them, and the works their drafts and names.
 */
export function useDictionaryChanged(typeId: string) {
  const queryClient = useQueryClient();
  return (worksChanged: boolean) =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: [UNIVERSES, typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facet-values"] }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      worksChanged && queryClient.invalidateQueries({ queryKey: [ITEMS, typeId] }),
    ]);
}

/**
 * The user's words on what of an entry is kept as characters: a class of its entries, or one
 * entry over its classes. A word that says what would be anyway is taken back instead of kept, so
 * a refresh of the catalogue still decides for everything the user did not set apart.
 */
export function useInclusion(typeId: string, e: EntryRef) {
  const changed = useDictionaryChanged(typeId);
  const path = { catalogue: e.catalogue, universe: e.universe };
  return {
    setClass: async (cls: UniverseClassInfo) => {
      const included = !cls.included;
      const params = { path: { ...path, class: cls.class } };
      ensureOk(
        included === cls.character
          ? await api.DELETE("/api/universes/{catalogue}/{universe}/classes/{class}", { params })
          : await api.PUT("/api/universes/{catalogue}/{universe}/classes/{class}", { params, body: { included } }),
      );
      await changed(false);
    },
    /** [byClasses]: whether the entry's classes keep it. */
    setEntry: async (c: CharacterInfo, byClasses: boolean) => {
      const included = !c.included;
      const params = { path: { ...path, character: c.character } };
      ensureOk(
        included === byClasses
          ? await api.DELETE("/api/universes/{catalogue}/{universe}/characters/{character}", { params })
          : await api.PUT("/api/universes/{catalogue}/{universe}/characters/{character}", { params, body: { included } }),
      );
      await changed(false);
    },
  };
}

/** What the entry's classes say of it: kept when any of them is, or when it has none. */
export const keptByClasses = (c: CharacterInfo, classes: Map<string, UniverseClassInfo>) =>
  c.classes.length === 0 || c.classes.some((id) => classes.get(id)?.included === true);
