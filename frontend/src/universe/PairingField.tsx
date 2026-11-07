import { type ReactNode, useState } from "react";
import { PlusIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { FacetValueInfo, ItemFacet, ItemRef } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { FieldBlock } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { valueName } from "@/i18n/label";
import { useCandidates } from "@/suggestion/useSuggestions";
import { AllNamed } from "./AllNamed";
import { type FieldSearch, has, ORIGINAL_CHARACTERS, PAIRINGS } from "./fanficFields";
import { type Option, Picker, ranked } from "./Picker";
import { Chips } from "./ValueChips";

/** The key of the pairing of two characters: one whatever their order. */
const pairingKey = (a: string, b: string) => `pair:${[a, b].sort().join("|")}`;

const SELECT =
  "h-8 min-w-0 flex-1 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring dark:bg-input/30";

/**
 * The work's pairings; a new one is two of the work's characters. Once the user says there are no
 * others ([complete]), the work's own alone are shown: every other is taken from it, nothing more
 * is offered.
 */
export function Pairings({
  item,
  field,
  label,
  values,
  complete,
  characters,
  search,
  onSearchClose,
  corrections,
}: {
  item: ItemRef;
  field: ItemFacet;
  label: string;
  values: FacetValueInfo[];
  complete: boolean;
  /** The characters the work has. */
  characters: FacetValueInfo[];
  /** The search a site's word of a pair opened. */
  search: FieldSearch | null;
  onSearchClose: () => void;
  corrections: Corrections;
}) {
  const own = values.filter(has);
  // Shown with no value too: "no pairings" is an answer as well
  const allNamed = <AllNamed facet={PAIRINGS} named="pairings" complete={complete} corrections={corrections} />;
  if (complete)
    return (
      <FieldBlock field={PAIRINGS} label={label} facet={field} corrections={corrections} tools={allNamed} sources={null}>
        <Chips facet={PAIRINGS} values={own} corrections={corrections} />
      </FieldBlock>
    );
  return (
    <OpenPairings
      item={item}
      field={field}
      label={label}
      values={values}
      characters={characters}
      search={search}
      onSearchClose={onSearchClose}
      corrections={corrections}
      allNamed={allNamed}
    />
  );
}

/** The work's pairings while others may be: every value, the picker and a pair of one's own. */
function OpenPairings({
  item,
  field,
  label,
  values,
  characters,
  search,
  onSearchClose,
  corrections,
  allNamed,
}: {
  item: ItemRef;
  field: ItemFacet;
  label: string;
  values: FacetValueInfo[];
  characters: FacetValueInfo[];
  search: FieldSearch | null;
  onSearchClose: () => void;
  corrections: Corrections;
  /** The button saying there are no others. */
  allNamed: ReactNode;
}) {
  const { t } = useTranslation("universe");
  const [composing, setComposing] = useState(false);
  // A site's word of a pair pressed: typed into the choice of pairs; with nothing to choose from, the composing opened
  const adding = composing || (search !== null && characters.length === 0);
  const closeComposing = () => {
    setComposing(false);
    onSearchClose();
  };
  const [a, setA] = useState("");
  const [b, setB] = useState("");
  // The work's characters and the original ones: they pair with anyone, listed among the main ones or not
  const members = [
    ...characters.map((c) => ({ key: c.key, name: valueName(c) })),
    ...ORIGINAL_CHARACTERS.filter((o) => !characters.some((c) => c.key === o.key)).map((o) => ({ key: o.key, name: t(o.label) })),
  ];
  const known = (key: string) => members.some((c) => c.key === key);
  const ready = known(a) && known(b) && a !== b;
  // Every pair of them the work lacks, the likeliest first: the backend pairs a work's characters once it has one
  const candidates = useCandidates(item, PAIRINGS, characters.length > 0);
  const pairs: Option[] = (candidates.data ?? []).map((c) => ({ key: c.key, name: valueName(c), names: [valueName(c)], taken: false }));

  const add = async () => {
    await corrections.setFacet(PAIRINGS, { key: pairingKey(a, b) }, true);
    setA("");
    setB("");
    closeComposing();
  };

  const select = (value: string, onChange: (value: string) => void, other: string) => (
    <select value={value} onChange={(e) => onChange(e.target.value)} className={SELECT}>
      <option value="">{t("OpenPairings.character")}</option>
      {members.map((c) => (
        <option key={c.key} value={c.key} disabled={c.key === other}>
          {c.name}
        </option>
      ))}
    </select>
  );

  return (
    <FieldBlock field={PAIRINGS} label={label} facet={field} corrections={corrections} tools={allNamed} sources={null}>
      <Chips facet={PAIRINGS} values={values} item={item} corrections={corrections} candidates={candidates.data} />
      {characters.length > 0 && (
        <Picker
          key={search?.press}
          word={search?.word}
          label={t("OpenPairings.choose")}
          placeholder={t("OpenPairings.placeholder")}
          options={ranked(pairs, candidates.data)}
          loading={candidates.isLoading}
          error={candidates.error?.message}
          empty={t("OpenPairings.allAnswered")}
          onPick={(key) => corrections.setFacet(PAIRINGS, { key }, true)}
        />
      )}
      {!adding ? (
        <div className="flex flex-wrap items-start gap-1">
          <Button variant="ghost" size="sm" onClick={() => setComposing(true)}>
            <PlusIcon /> {t("OpenPairings.compose")}
          </Button>
        </div>
      ) : (
        // biome-ignore lint/a11y/noStaticElementInteractions: not a control — the box only keeps the choice of the pair from the keys of the work's screen
        <div className="flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            {t("OpenPairings.composeHint")}
            <Button variant="ghost" size="icon-xs" onClick={closeComposing} title={t("OpenPairings.close")}>
              <XIcon />
            </Button>
          </div>
          <div className="flex items-center gap-1">
            {select(a, setA, b)}
            <span className="text-muted-foreground">/</span>
            {select(b, setB, a)}
            <AsyncButton size="icon" variant="outline" onClick={add} disabled={!ready} title={t("OpenPairings.add")} icon={<PlusIcon />} />
          </div>
        </div>
      )}
    </FieldBlock>
  );
}
