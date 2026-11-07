import { Fragment } from "react";
import type { Candidate, FacetValueInfo, ItemRef, SuggestedValue } from "@/api/client";
import { FacetValue, LaterOffers, MoreCandidates, SearchButton } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { useLaterOffer, useStableOrder } from "@/facet/valueOrder";
import { SuggestedChip } from "@/suggestion/Suggestions";
import { CHARACTERS, PAIRINGS, SECONDARY_CHARACTERS } from "./fanficFields";

/** The fields whose values the work's texts name — a character, two in one paragraph: "…" offers the most named beside the likeliest. */
const BY_TEXT: readonly string[] = [CHARACTERS, SECONDARY_CHARACTERS, PAIRINGS];

/**
 * The work's values of a facet as chips, the suggested ones dashed after them, a dash when there
 * are none; with [item], "…" for more of its candidates; with [search], a 🔍 after it that opens
 * the search of a value by its name; with [candidates], how often the texts name each.
 */
export function Chips({
  facet,
  values,
  suggested = [],
  item,
  corrections,
  search,
  candidates,
}: {
  facet: string;
  values: FacetValueInfo[];
  suggested?: SuggestedValue[];
  item?: ItemRef;
  corrections: Corrections;
  search?: { onClick: () => void; title: string };
  candidates?: Candidate[];
}) {
  const ordered = useStableOrder(values);
  const later = useLaterOffer(ordered);
  if (values.length === 0 && suggested.length === 0 && !item) return <div className="text-sm text-muted-foreground">—</div>;
  return (
    <div className="flex flex-wrap items-center gap-1">
      {values.length === 0 && suggested.length === 0 && <span className="text-sm text-muted-foreground">—</span>}
      {ordered.map((v) => (
        <Fragment key={v.key}>
          {v.key === later && <LaterOffers />}
          <FacetValue facet={facet} value={v} corrections={corrections} mentions={candidates?.find((c) => c.key === v.key)?.mentions} />
        </Fragment>
      ))}
      {suggested.map((v) => (
        <SuggestedChip key={v.key} facet={facet} value={v} corrections={corrections} />
      ))}
      {item && <MoreCandidates item={item} facet={facet} shown={[...values, ...suggested]} corrections={corrections} byText={BY_TEXT.includes(facet)} />}
      {search && <SearchButton onClick={search.onClick} title={search.title} />}
    </div>
  );
}
