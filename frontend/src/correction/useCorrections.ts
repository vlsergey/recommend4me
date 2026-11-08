import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import {
  api,
  ensureOk,
  refKey,
  unwrap,
  type AnsweredFacet,
  type Candidate,
  type CatalogueEntryRef,
  type FacetSuggestions,
  type FacetValueInfo,
  type ItemDetails,
  type ItemRef,
  type TagByText,
  type TagByTextVerdict,
} from "@/api/client";
import { applyDetails, ITEMS, itemKey } from "@/item/lists";
import { heldOutLater } from "@/item/useHeldOut";
import { answerKept, keptOn, readWork } from "@/item/workRead";
import { candidatesKey, suggestionsKey } from "@/suggestion/useSuggestions";
import { useTagsChanged } from "@/tag/tagQueries";
import { tagsByTextKey } from "@/tagtext/TagsByText";

/** The fields a correction overrides: the title, a text, a number. */
export const titleField = "title";
export const textField = (key: string) => `text:${key}`;
export const numberField = (key: string) => `number:${key}`;

/** The quiet after the last answer on a work after which it is read anew: answers given one after another wait for no reading. */
const SETTLE_MS = 2000;

/** The read of every work pending after its answers, by "source/item". */
const pending = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * Once no answer on the work has come for [SETTLE_MS]: the counts of the filters, its suggestions
 * and candidates read anew, and the work itself — its chances made of the answers as it is read.
 * The answers are shown meanwhile as kept; nothing is read between answers, and a read an answer
 * came during shows nothing older than the page does ([readWork]).
 */
function settleLater(queryClient: QueryClient, item: ItemRef, typeId: string) {
  const key = refKey(item);
  clearTimeout(pending.get(key));
  pending.set(
    key,
    setTimeout(() => {
      pending.delete(key);
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
      queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
      queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
      heldOutLater(queryClient, item);
      // The work as the application now has it, in the dialog and in the lists alike
      readWork(queryClient, item).then(
        (read) => applyDetails(queryClient, read, item),
        () => undefined,
      );
    }, SETTLE_MS),
  );
}

type Shown = Pick<FacetValueInfo, "name" | "nameKey">;

/** What a value answered on is shown by, of what the page shows already: the work's values, its suggestions, its candidates. */
function shownName(queryClient: QueryClient, item: ItemRef, facet: string, key: string): Shown | undefined {
  const details = queryClient.getQueryData<ItemDetails>(itemKey(item));
  const own = details?.allFacets.find((f) => f.facet === facet)?.values.find((v) => v.key === key);
  if (own) return { name: own.name, nameKey: own.nameKey };
  const suggested = queryClient.getQueryData<FacetSuggestions[]>(suggestionsKey(item))?.find((s) => s.facet === facet)?.suggested.find((v) => v.key === key);
  if (suggested) return { name: suggested.name, nameKey: suggested.nameKey };
  // Of every reading of the facet's candidates: all of them, or the likeliest few
  const candidate = queryClient
    .getQueriesData<Candidate[]>({ queryKey: candidatesKey(item, facet).slice(0, -1) })
    .flatMap(([, data]) => data ?? [])
    .find((c) => c.key === key);
  return candidate && { name: candidate.name, nameKey: candidate.nameKey };
}

/** Given by the site, the model or a draft before any answer: confirmed by a yes, not added. */
const given = (v: FacetValueInfo) => (v.corrected == null && !v.offered && !v.implied) || (v.chance ?? 0) > 0.5 || v.draftedFrom != null;

/**
 * A facet of the work as the answers kept make it ([answered]), as the page shows it until the work
 * is read anew — the values' chances and the sites' word as shown: a value answered on is the
 * user's — CONFIRMED of one the site, the model or a draft gave, ADDED of any other — or REMOVED,
 * crossed out in its place; one whose answer was taken back is the site's and the model's again, or
 * goes when only the user gave it; one shown no more ([AnsweredFacet.hidden]) goes, and so does
 * every other of a facet of one value a value was added to; a tag the work has by a tag under it
 * the user confirmed is marked so — not one the work has of itself — and shown if it was not.
 */
function withAnswers(f: ItemDetails["allFacets"][number], answered: AnsweredFacet, named: (key: string) => Shown | undefined) {
  const answers = new Map(answered.answers.map((a) => [a.key, a]));
  const hidden = new Set(answered.hidden);
  const implied = new Map(answered.implied.map((v) => [v.key, v]));
  const replaced = f.single === true && answered.answers.some((a) => a.added);
  const values = f.values.flatMap((v): FacetValueInfo[] => {
    const a = answers.get(v.key);
    if (a) {
      if (!a.added) return [{ ...v, corrected: "REMOVED", offered: undefined, implied: undefined }];
      const corrected = v.corrected === "CONFIRMED" || v.corrected === "ADDED" ? v.corrected : given(v) ? "CONFIRMED" : "ADDED";
      return [{ ...v, corrected, offered: undefined, implied: undefined }];
    }
    if (hidden.has(v.key) || replaced) return [];
    if (v.corrected) return v.corrected === "ADDED" && !given(v) ? [] : [{ ...v, corrected: undefined }];
    if (implied.has(v.key) && (v.offered || v.implied)) return [{ ...v, implied: true, offered: undefined }];
    return [v];
  });
  const known = new Set(values.map((v) => v.key));
  const more: FacetValueInfo[] = [
    ...answered.answers
      .filter((a) => !known.has(a.key))
      .map((a): FacetValueInfo => {
        const shown = named(a.key);
        return { key: a.key, name: shown?.name ?? a.name ?? a.key, nameKey: shown?.nameKey, corrected: a.added ? "ADDED" : "REMOVED" };
      }),
    ...answered.implied.filter((v) => !known.has(v.key) && !answers.has(v.key)).map((v): FacetValueInfo => ({ key: v.key, name: v.name, nameKey: v.nameKey, implied: true })),
  ];
  return { ...f, values: [...values, ...more] };
}

/** The suggestions of the work without the values answered on: answered, they are no longer suggestions. */
function withoutSuggested(list: FacetSuggestions[] | undefined, facet: string, keys: Set<string>): FacetSuggestions[] | undefined {
  return list?.map((s) => (s.facet === facet ? { ...s, suggested: s.suggested.filter((v) => !keys.has(v.key)) } : s));
}

/**
 * The user's corrections of one item. Each returns once the item is saved; a failure throws, for the
 * button that asked to show it. An answer on a facet is only written: the page shows it whole once
 * kept, by what the application says the answers make of the facets it touched, and reads the work
 * anew once the answers have settled ([settleLater]).
 */
export function useCorrections(item: ItemRef, typeId: string) {
  const queryClient = useQueryClient();
  const tagsChanged = useTagsChanged();
  const path = { source: item.source, item: item.item };

  /**
   * The work as a change of a field or a link left it, read by the application after it ([details]):
   * shown, unless an answer was kept while it was read ([before], [keptOn]) — the page shows that
   * answer already, and the read after the answers' quiet brings both.
   */
  const done = (details: ItemDetails, before: number) => {
    if (keptOn(item) !== before) {
      settleLater(queryClient, item, typeId);
      return details;
    }
    applyDetails(queryClient, details, item);
    // The counts of the filter values change with the values of the item
    queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
    // The held-out model is slow to fit: read anew only once the answers have settled
    heldOutLater(queryClient, item);
    return details;
  };

  /** A change of the work read back: [change] sent, the work it answers with shown by [done]. */
  const changed = async (change: () => Promise<ItemDetails>) => {
    const before = keptOn(item);
    return done(await change(), before);
  };

  /** A change of the work as shown, kept: shown now, the work read anew after the quiet. */
  const shownKept = (change: (d: ItemDetails) => ItemDetails, answeredOn?: { facet: string; keys: Set<string> }) => {
    answerKept(item);
    queryClient.setQueryData<ItemDetails>(itemKey(item), (d) => d && change(d));
    if (answeredOn) queryClient.setQueryData<FacetSuggestions[]>(suggestionsKey(item), (list) => withoutSuggested(list, answeredOn.facet, answeredOn.keys));
    settleLater(queryClient, item, typeId);
  };

  /** Answers kept: the facets they touched shown as the application says the answers make them ([withAnswers]). */
  const shownAnswers = (answered: AnsweredFacet[], name?: { key: string; name: string }) => {
    const named = (facet: string) => (key: string) => (name?.key === key ? { name: name.name } : shownName(queryClient, item, facet, key));
    shownKept(
      (d) => ({
        ...d,
        allFacets: d.allFacets.map((f) => {
          const a = answered.find((x) => x.facet === f.facet);
          return a ? withAnswers(f, a, named(f.facet)) : f;
        }),
      }),
      answered[0] && { facet: answered[0].facet, keys: new Set(answered[0].answers.map((a) => a.key)) },
    );
  };

  return {
    setField: (field: string, content: string) =>
      changed(async () => unwrap(await api.PUT("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } }, body: { content } }))),

    resetField: (field: string) =>
      changed(async () => unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } } }))),

    /**
     * Adds a value of a facet ([added]) or takes away one the site gives. A value is named by its
     * key, or by its name alone: the backend finds it among the facet's values or makes it up; or,
     * of a facet of tags, by an entry of a catalogue: the tag bound to it, made of it when none is.
     */
    setFacet: async (facet: string, value: { key?: string; name?: string; entry?: CatalogueEntryRef }, added: boolean) => {
      const answer = unwrap(await api.PUT("/api/items/{source}/{item}/corrections/facets", { params: { path }, body: { facet, ...value, added } }));
      shownAnswers(answer.facets, answer.name ? { key: answer.key, name: answer.name } : undefined);
    },

    resetFacet: async (facet: string, key: string) => {
      shownAnswers(unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/facets", { params: { path, query: { facet, key } } })).facets);
    },

    /**
     * The user's word on a tag offered by a paragraph of the work's text ([verdict]): the pair is
     * offered no more, the list read anew once it is kept; of a yes, the tag is answered on the work
     * as added, shown as any answer is.
     */
    answerTagByText: async (tag: TagByText, verdict: TagByTextVerdict) => {
      const answered = unwrap(
        await api.PUT("/api/items/{source}/{item}/tags/by-text/answer", {
          params: { path },
          body: { key: tag.key, part: tag.part, paragraph: tag.paragraph, verdict },
        }),
      );
      queryClient.invalidateQueries({ queryKey: tagsByTextKey(item) });
      if (answered.length > 0) shownAnswers(answered, { key: tag.key, name: tag.name });
    },

    /**
     * The tag ignored altogether, as the bin of the screen of the tags does: offered nowhere, read by
     * no model — gone from the work's values at once, but where the user answered on it.
     */
    ignoreTag: async (facet: string, key: string) => {
      unwrap(await api.PUT("/api/tags/ignored", { body: { tag: key, ignored: true } }));
      shownKept(
        (d) => ({ ...d, allFacets: d.allFacets.map((f) => (f.facet !== facet ? f : { ...f, values: f.values.filter((v) => v.key !== key || v.corrected) })) }),
        { facet, keys: new Set([key]) },
      );
      // The tags' screens and the counts read anew; the work after the quiet, with every other answer
      await tagsChanged({ works: false });
    },

    /** The work's values of the facet are all named ([complete]): every other is taken from it — or that is taken back. */
    setComplete: async (facet: string, complete: boolean) => {
      ensureOk(
        complete
          ? await api.PUT("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } })
          : await api.DELETE("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } }),
      );
      shownKept((d) => ({
        ...d,
        allFacets: d.allFacets.map((f) =>
          // Said complete, nothing more is offered
          f.facet !== facet ? f : { ...f, complete: complete || undefined, values: complete ? f.values.filter((v) => !v.offered) : f.values },
        ),
      }));
    },

    /** The other item is the same work: both keep their cards, which now name each other — the lists are asked for anew. */
    link: async (other: ItemRef) => {
      const details = await changed(async () =>
        unwrap(await api.POST("/api/items/{source}/{item}/links", { params: { path }, body: { source: other.source, item: other.item } })),
      );
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      return details;
    },

    /** The other item is another work again: the lists are asked for anew. */
    unlink: async (other: ItemRef) => {
      const details = await changed(async () =>
        unwrap(await api.DELETE("/api/items/{source}/{item}/links", { params: { path, query: { otherSource: other.source, otherItem: other.item } } })),
      );
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      return details;
    },
  };
}

export type Corrections = ReturnType<typeof useCorrections>;
