import type { Candidate, FacetValueInfo, ItemRef, SuggestedValue } from "@/api/client";
import { SearchButton, ValueLine } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
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
  if (values.length === 0 && suggested.length === 0 && !item) return <div className="text-sm text-muted-foreground">—</div>;
  return (
    <ValueLine
      item={item}
      facet={facet}
      values={values}
      suggested={suggested}
      corrections={corrections}
      byText={BY_TEXT.includes(facet)}
      mentions={(key) => candidates?.find((c) => c.key === key)?.mentions}
    >
      {values.length === 0 && suggested.length === 0 && <span className="order-first text-sm text-muted-foreground">—</span>}
      {search && <SearchButton onClick={search.onClick} title={search.title} />}
    </ValueLine>
  );
}
