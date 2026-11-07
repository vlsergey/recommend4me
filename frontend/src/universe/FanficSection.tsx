import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, ItemDetails, ItemFacet, SourceInfo } from "@/api/client";
import { saidTogether, SiteWords, type WordTarget } from "@/correction/SiteWords";
import type { Corrections } from "@/correction/useCorrections";
import { label as labelOfKey } from "@/i18n/label";
import { useSuggestions } from "@/suggestion/useSuggestions";
import { Characters } from "./CharacterField";
import { CHARACTERS, type FieldSearch, has, KIND, PAIRINGS, SECONDARY_CHARACTERS, UNIVERSE, universeless } from "./fanficFields";
import { KindChoice } from "./KindChoice";
import { Pairings } from "./PairingField";
import { Universes } from "./UniverseField";

/** What the screen of a work and the screen of the pairings take of the fields of a fanfic's universe. */
export { has, ORIGINAL_CHARACTERS, universeless } from "./fanficFields";
export { type Option, Picker } from "./Picker";

/** The facets this section answers: the generic list of the work's facets leaves them out. */
export const FANFIC_FACETS: readonly string[] = [KIND, UNIVERSE, CHARACTERS, SECONDARY_CHARACTERS, PAIRINGS];

/** The fields closed by "No other …". */
export const NAMED_FACETS: readonly string[] = [UNIVERSE, CHARACTERS, SECONDARY_CHARACTERS, PAIRINGS];

/**
 * A group of fields of the form under its heading, with [sources] when its fields read the same words
 * of the sites: they stand once, over the fields. The [first] field's key then leads to the group, the
 * line of fields coming to the sources and not past them.
 */
export function FieldGroup({ title, first, sources, children }: { title: string; first?: string; sources?: ReactNode; children: ReactNode }) {
  return (
    <section data-field={first} className="flex scroll-mt-24 flex-col gap-3">
      <h3 className="border-b pb-0.5 text-sm font-semibold">{title}</h3>
      {sources}
      {children}
    </section>
  );
}

/**
 * Whether the work is fan fiction, of which universes, with which main characters and pairings: the
 * fields of the form in the order their answers depend on each other, each group under what every
 * site says of its fields. For the works of a type with universes only.
 */
export function FanficSection({
  type,
  source,
  details: d,
  corrections,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  corrections: Corrections;
}) {
  const { t } = useTranslation("universe");
  const ref = { source: d.summary.source, item: d.summary.item };
  const suggestions = useSuggestions(ref, source);
  const facetOf = (key: string) => d.allFacets.find((f) => f.facet === key);
  const valuesOf = (key: string) => facetOf(key)?.values ?? [];
  const suggestedOf = (key: string) => suggestions.data?.find((s) => s.facet === key)?.suggested ?? [];
  const labelOf = (key: string, fallback: string) => {
    const labelKey = facetOf(key)?.labelKey ?? source.facets.find((f) => f.key === key)?.labelKey;
    return labelKey ? labelOfKey(labelKey) : fallback;
  };

  const original = universeless(valuesOf(KIND).filter(has));
  /** A field's facet: every facet of the layer comes, with no value too. */
  const fieldOf = (key: string, fallback: string): ItemFacet => facetOf(key) ?? { facet: key, labelKey: fallback, editable: true, values: [] };

  // The search a site's word opens is in a field, the word in the sources over its group
  const [search, setSearch] = useState<FieldSearch | null>(null);
  const open = (facet: string, word: string) => setSearch({ facet, word, press: (search?.press ?? 0) + 1 });
  const close = () => setSearch(null);
  const searchOf = (facet: string) => (search?.facet === facet ? search : null);
  /** A field a site's word goes to, while it is open. */
  const target = (facet: string, label: string, names: boolean): WordTarget[] => (facetOf(facet)?.complete ? [] : [{ label, names, onWord: (word) => open(facet, word) }]);
  const mainLabel = labelOf(CHARACTERS, t("Facet.mainCharacters"));
  const secondaryLabel = labelOf(SECONDARY_CHARACTERS, t("Facet.secondaryCharacters"));
  const pairingsLabel = labelOf(PAIRINGS, t("Facet.pairings"));
  const universeLabel = labelOf(UNIVERSE, t("Facet.universe"));

  return (
    <>
      <FieldGroup
        title={t("Form.world")}
        first={KIND}
        sources={
          <SiteWords
            facet={saidTogether(original ? [fieldOf(KIND, "")] : [fieldOf(UNIVERSE, ""), fieldOf(KIND, "")])}
            targets={original ? [] : target(UNIVERSE, universeLabel, false)}
            bindTo={original ? undefined : valuesOf(UNIVERSE)}
          />
        }
      >
        <KindChoice label={labelOf(KIND, t("Facet.kind"))} facet={fieldOf(KIND, "")} suggested={suggestedOf(KIND)} corrections={corrections} />
        {!original && (
          <Universes
            type={type}
            label={universeLabel}
            facet={fieldOf(UNIVERSE, "")}
            values={valuesOf(UNIVERSE)}
            complete={facetOf(UNIVERSE)?.complete === true}
            suggested={suggestedOf(UNIVERSE)}
            search={searchOf(UNIVERSE)}
            corrections={corrections}
          />
        )}
      </FieldGroup>
      <FieldGroup
        title={t("Form.characters")}
        first={CHARACTERS}
        sources={
          <SiteWords
            facet={saidTogether([fieldOf(CHARACTERS, ""), fieldOf(SECONDARY_CHARACTERS, ""), fieldOf(PAIRINGS, "")])}
            targets={[...target(CHARACTERS, mainLabel, true), ...target(SECONDARY_CHARACTERS, secondaryLabel, true), ...target(PAIRINGS, pairingsLabel, false)]}
          />
        }
      >
        <Characters
          type={type}
          item={ref}
          field={fieldOf(CHARACTERS, "")}
          facet={CHARACTERS}
          label={mainLabel}
          named="mainCharacters"
          values={valuesOf(CHARACTERS)}
          complete={facetOf(CHARACTERS)?.complete === true}
          elsewhere={valuesOf(SECONDARY_CHARACTERS).filter(has)}
          universes={valuesOf(UNIVERSE).filter(has)}
          search={searchOf(CHARACTERS)}
          onSearch={(word) => open(CHARACTERS, word)}
          onSearchClose={close}
          corrections={corrections}
        />
        <Characters
          type={type}
          item={ref}
          field={fieldOf(SECONDARY_CHARACTERS, "")}
          facet={SECONDARY_CHARACTERS}
          label={secondaryLabel}
          named="secondaryCharacters"
          values={valuesOf(SECONDARY_CHARACTERS)}
          complete={facetOf(SECONDARY_CHARACTERS)?.complete === true}
          elsewhere={valuesOf(CHARACTERS).filter(has)}
          universes={valuesOf(UNIVERSE).filter(has)}
          search={searchOf(SECONDARY_CHARACTERS)}
          onSearch={(word) => open(SECONDARY_CHARACTERS, word)}
          onSearchClose={close}
          corrections={corrections}
        />
        <Pairings
          item={ref}
          field={fieldOf(PAIRINGS, "")}
          label={pairingsLabel}
          values={valuesOf(PAIRINGS)}
          complete={facetOf(PAIRINGS)?.complete === true}
          characters={valuesOf(CHARACTERS).filter(has)}
          search={searchOf(PAIRINGS)}
          onSearchClose={close}
          corrections={corrections}
        />
      </FieldGroup>
    </>
  );
}
