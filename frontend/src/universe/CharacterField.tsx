import type { ReactNode } from "react";
import { useQueries } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, FacetValueInfo, ItemFacet, ItemRef, UniverseInfo } from "@/api/client";
import { FieldBlock } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { valueName } from "@/i18n/label";
import { useCandidates } from "@/suggestion/useSuggestions";
import { AllNamed } from "./AllNamed";
import { CHARACTERS, type FieldSearch, has, ORIGINAL_CHARACTERS } from "./fanficFields";
import { type Option, Picker, ranked } from "./Picker";
import { charactersQuery, useUniverses } from "./Universes";
import { Chips } from "./ValueChips";

/**
 * The work's main or secondary characters ([facet]). Once the user says there are no others
 * ([complete]), the work's own alone are shown: every other is taken from it, nothing more is
 * offered.
 */
export function Characters({
  type,
  item,
  field,
  facet,
  label,
  named,
  values,
  complete,
  elsewhere,
  universes,
  search,
  onSearch,
  onSearchClose,
  corrections,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  field: ItemFacet;
  facet: string;
  label: string;
  /** Which characters these are, for the button saying there are no others: "No other main characters". */
  named: "mainCharacters" | "secondaryCharacters";
  values: FacetValueInfo[];
  complete: boolean;
  /** The characters the work has in its other facet of characters: a character is in one of them only. */
  elsewhere: FacetValueInfo[];
  /** The universes the work has. */
  universes: FacetValueInfo[];
  /** The search of a character by name, open: by a site's word, or by the 🔍 after the work's characters. */
  search: FieldSearch | null;
  onSearch: (word: string) => void;
  onSearchClose: () => void;
  corrections: Corrections;
}) {
  const own = values.filter(has);
  // Shown with no value too: "no main characters" is an answer as well
  const allNamed = <AllNamed facet={facet} named={named} complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <FieldBlock field={facet} label={label} facet={field} corrections={corrections} tools={allNamed} sources={null}>
        <Chips facet={facet} values={own} corrections={corrections} />
      </FieldBlock>
    );
  return (
    <OpenCharacters
      type={type}
      item={item}
      field={field}
      facet={facet}
      label={label}
      values={values}
      elsewhere={elsewhere}
      universes={universes}
      search={search}
      onSearch={onSearch}
      onSearchClose={onSearchClose}
      corrections={corrections}
      allNamed={allNamed}
    />
  );
}

/**
 * The work's characters of the [facet] while others may be. The picker offers the characters of
 * every universe the work is linked to, by any of their names — those of its other facet of
 * characters too, said so: picked, one moves here — the original characters, and a name of the
 * user's own.
 */
function OpenCharacters({
  type,
  item,
  field,
  facet,
  label,
  values,
  elsewhere,
  universes,
  search,
  onSearch,
  onSearchClose,
  corrections,
  allNamed,
}: {
  type: ContentTypeInfo;
  item: ItemRef;
  field: ItemFacet;
  facet: string;
  label: string;
  values: FacetValueInfo[];
  elsewhere: FacetValueInfo[];
  universes: FacetValueInfo[];
  search: FieldSearch | null;
  onSearch: (word: string) => void;
  onSearchClose: () => void;
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const { t } = useTranslation("universe");
  const dictionary = useUniverses(type.id, universes.length > 0);
  const linked = universes
    .map((v) => dictionary.data?.find((u) => u.value === v.key))
    .filter((u): u is UniverseInfo => u !== undefined);
  const characters = useQueries({ queries: linked.map((u) => charactersQuery(type.id, u)) });

  const taken = (key: string) => values.some((v) => v.key === key && has(v));
  // A character of the work's other facet of characters — the user's or the model's — is offered all
  // the same, said so: picked, it moves here from there
  const other = new Set(elsewhere.map((v) => v.key));
  const moving = t(facet === CHARACTERS ? "OpenCharacters.fromSecondary" : "OpenCharacters.fromMain");
  // A character of several of the work's universes is offered once, with every universe it is of;
  // the entries the user left out of a universe (its places, its spells) are not offered
  const byKey = new Map<string, Option & { universes: string[] }>();
  linked.forEach((u, i) => {
    for (const c of (characters[i].data ?? []).filter((c) => c.included)) {
      const key = `${u.catalogue}:${c.character}`;
      const known = byKey.get(key);
      if (known) known.universes.push(u.name);
      else
        byKey.set(key, {
          key,
          name: c.names[0] ?? c.character,
          description: c.description ?? undefined,
          names: c.names.length > 0 ? c.names : [c.character],
          universes: [u.name],
          taken: taken(key),
        });
    }
  });
  // One of the other facet no linked universe has — named by the user, of a universe since unlinked — offered too
  const unlinked = elsewhere.filter((v) => !byKey.has(v.key) && !ORIGINAL_CHARACTERS.some((c) => c.key === v.key));
  const options: Option[] = [
    ...[...byKey.values()].map((o) => ({
      ...o,
      note: [o.names.slice(1).join(", "), o.universes.join(", "), other.has(o.key) ? moving : ""].filter((x) => x !== "").join(" — "),
    })),
    ...unlinked.map((v) => ({ key: v.key, name: valueName(v), names: [valueName(v)], note: moving, taken: false })),
    ...ORIGINAL_CHARACTERS.map((c) => ({ key: c.key, name: t(c.label), names: [t(c.label)], taken: taken(c.key) })),
  ];
  const candidates = useCandidates(item, facet, true);
  const failed = characters.find((q) => q.isError)?.error?.message ?? dictionary.error?.message ?? candidates.error?.message;

  return (
    <FieldBlock field={facet} label={label} facet={field} corrections={corrections} tools={allNamed} sources={null}>
      <Chips
        facet={facet}
        values={values}
        item={item}
        corrections={corrections}
        candidates={candidates.data}
        search={{ onClick: () => (search ? onSearchClose() : onSearch("")), title: t("OpenCharacters.add") }}
      />
      {universes.length === 0 && <p className="text-xs text-muted-foreground">{t("OpenCharacters.noUniverse")}</p>}
      {search && (
        <Picker
          key={search.press}
          word={search.word}
          label={t("OpenCharacters.add")}
          placeholder={t("OpenCharacters.placeholder")}
          options={ranked(options, candidates.data)}
          loading={dictionary.isLoading || characters.some((q) => q.isLoading) || candidates.isLoading}
          error={failed}
          empty={universes.length === 0 ? t("OpenCharacters.noUniverse") : undefined}
          onPick={(key) => corrections.setFacet(facet, { key }, true)}
          onName={(name) => corrections.setFacet(facet, { name }, true)}
          onClose={onSearchClose}
        />
      )}
    </FieldBlock>
  );
}
