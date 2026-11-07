import type { ReactNode } from "react";
import { SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, FacetValueInfo, ItemFacet, SuggestedValue } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { FieldBlock } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { AllNamed } from "./AllNamed";
import { type FieldSearch, has, UNIVERSE } from "./fanficFields";
import { type Option, Picker } from "./Picker";
import { CatalogueSearch, useUniverses } from "./Universes";
import { Chips } from "./ValueChips";

/**
 * The work's universes and the suggested ones; a universe of the dictionary is added by the
 * picker, one not yet in the dictionary is found in the catalogue — added to the dictionary and
 * linked to the work at once. Once the user says there are no others ([complete]), the work's own
 * alone are shown: every other is taken from it, nothing more is offered.
 */
export function Universes({
  type,
  label,
  facet,
  values,
  complete,
  suggested,
  search,
  corrections,
}: {
  type: ContentTypeInfo;
  label: string;
  facet: ItemFacet;
  values: FacetValueInfo[];
  complete: boolean;
  suggested: SuggestedValue[];
  /** The search a site's word opened. */
  search: FieldSearch | null;
  corrections: Corrections;
}) {
  const own = values.filter(has);
  // Shown with no value too: "no universe of another's" is an answer as well
  const allNamed = <AllNamed facet={UNIVERSE} named="universes" complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <FieldBlock field={UNIVERSE} label={label} facet={facet} corrections={corrections} tools={allNamed} sources={null}>
        <Chips facet={UNIVERSE} values={own} corrections={corrections} />
      </FieldBlock>
    );
  return <OpenUniverses type={type} label={label} facet={facet} values={values} suggested={suggested} search={search} corrections={corrections} allNamed={allNamed} />;
}

/** The work's universes while others may be: every value, the suggested ones, the picker and the search in the catalogue. */
function OpenUniverses({
  type,
  label,
  facet,
  values,
  suggested,
  search,
  corrections,
  allNamed,
}: {
  type: ContentTypeInfo;
  label: string;
  facet: ItemFacet;
  values: FacetValueInfo[];
  suggested: SuggestedValue[];
  search: FieldSearch | null;
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const { t } = useTranslation("universe");
  // A site's word pressed: typed into the picker and the catalogue's search both, the dictionary holding few of the fandoms
  const word = search?.word;
  const dictionary = useUniverses(type.id);
  const linked = (value: string) => values.some((v) => v.key === value && has(v));
  const link = (value: string) => corrections.setFacet(UNIVERSE, { key: value }, true);
  const options: Option[] = (dictionary.data ?? []).map((u) => ({
    key: u.value,
    name: u.name,
    note: u.description,
    names: [u.name],
    taken: linked(u.value),
  }));

  return (
    <FieldBlock field={UNIVERSE} label={label} facet={facet} corrections={corrections} tools={allNamed} sources={null}>
      <Chips facet={UNIVERSE} values={values} suggested={suggested} corrections={corrections} />
      <div className="flex flex-wrap items-start gap-1">
        <Picker
          key={search?.press}
          label={t("OpenUniverses.add")}
          word={word}
          placeholder={t("OpenUniverses.placeholder")}
          options={options}
          loading={dictionary.isLoading}
          error={dictionary.error?.message}
          empty={t("OpenUniverses.empty")}
          onPick={link}
        />
        <Dialog>
          <DialogTrigger render={<Button variant={word ? "outline" : "ghost"} size="sm" title={t("OpenUniverses.searchHint")} />}>
            <SearchIcon /> {word ? t("OpenUniverses.searchWord", { word }) : t("OpenUniverses.search")}
          </DialogTrigger>
          <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
            <DialogHeader>
              <DialogTitle>{t("OpenUniverses.searchTitle")}</DialogTitle>
              <DialogDescription>{t("OpenUniverses.searchDescription")}</DialogDescription>
            </DialogHeader>
            {/* The keys of the work's dialog (grades, steps) stay out of the search; Escape still closes it */}
            {/* biome-ignore lint/a11y/noStaticElementInteractions: not a control — the box only keeps the typing in the search from the keys of the work's screen */}
            <div onKeyDown={(e) => e.key !== "Escape" && e.stopPropagation()}>
              <CatalogueSearch type={type} dictionary={dictionary.data} forWork={{ linked, link }} word={word} />
            </div>
          </DialogContent>
        </Dialog>
      </div>
    </FieldBlock>
  );
}
