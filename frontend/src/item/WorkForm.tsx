import { useTranslation } from "react-i18next";
import { CheckIcon } from "lucide-react";
import type { ContentTypeInfo, ItemDetails, ItemFacet, ItemRef, SourceInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { FacetLine } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { decided } from "@/facet/valueOrder";
import { label } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { useSuggestions } from "@/suggestion/useSuggestions";
import { FANFIC_FACETS, FanficSection, FieldGroup, has, NAMED_FACETS, universeless } from "@/universe/FanficSection";
import { WorkFields } from "./WorkFields";

/** The facets the user closes by "No other …": others are done when nothing is left to answer. */
const closing = (f: ItemFacet) => f.completable === true || NAMED_FACETS.includes(f.facet);

/** What is left of a field: closed, so many to answer, or nothing left and not closed. */
function stateOf(f: ItemFacet): { closed: boolean; open: number } {
  const open = f.values.filter((v) => !decided(v) && !v.offered && !v.implied).length;
  const answered = f.values.some((v) => v.corrected === "ADDED" || v.corrected === "CONFIRMED");
  if (closing(f)) return { closed: f.complete === true, open };
  // Of one value: closed by the user's choice, never by the sites' agreeing
  if (f.single || f.steps?.length || f.facet === "kind") return { closed: answered, open: answered ? 0 : open };
  return { closed: open === 0 && f.values.length > 0, open };
}

/** The tags first, then a scale, then the rest, as a person reads a work. */
const rank = (f: ItemFacet) => (f.tags ? 0 : f.steps?.length ? 1 : 2);

/**
 * THE FORM OF A WORK (docs/work-screen.md): every field in the order the answers depend on each
 * other, each under what every site says of it, and over them a line telling at a look what is
 * closed and what is left. It is of the work: the site read on the left does not change it.
 */
export function WorkForm({
  type,
  source,
  details: d,
  item,
  corrections,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  item: ItemRef;
  corrections: Corrections;
}) {
  const { t } = useTranslation("item");
  const suggestions = useSuggestions(item, source);
  const layer = d.allFacets.filter((f) => f.editable);
  const rest = layer.filter((f) => !type.universes || !FANFIC_FACETS.includes(f.facet));
  const ordered = [...rest].sort((a, b) => rank(a) - rank(b));
  const fanfic = type.universes ? FANFIC_FACETS.map((key) => layer.find((f) => f.facet === key)).filter((f): f is ItemFacet => f !== undefined) : [];
  const hidden = universeless(layer.find((f) => f.facet === "kind")?.values.filter(has) ?? []);
  const fields = [...fanfic.filter((f) => !(hidden && f.facet === "universe")), ...ordered];
  return (
    <div className="flex flex-col gap-4">
      <nav
        className="sticky -top-3 z-10 -mx-3 -mt-3 flex items-center gap-1 overflow-x-auto border-b bg-background px-3 py-1 whitespace-nowrap [scrollbar-width:none] min-[56rem]:flex-wrap"
        aria-label={t("WorkForm.fields")}
      >
        {fields.map((f) => {
          const { closed, open } = stateOf(f);
          return (
            <Button
              key={f.facet}
              variant="ghost"
              size="xs"
              className={cn("shrink-0 pointer-coarse:h-11", !closed && open === 0 && "font-normal text-muted-foreground")}
              title={closed ? t("WorkForm.closed") : open > 0 ? t("WorkForm.open", { count: open }) : t("WorkForm.nothingLeft")}
              onClick={() => document.querySelector(`[data-field="${CSS.escape(f.facet)}"]`)?.scrollIntoView({ block: "start" })}
            >
              {closed && <CheckIcon className="text-yes" />}
              {label(f.labelKey)}
              {!closed && open > 0 && <span className="tabular-nums text-primary">{open}</span>}
            </Button>
          );
        })}
      </nav>
      {d.sites.length > 1 && <p className="text-xs text-muted-foreground">{t("WorkSites.markingOfWork")}</p>}
      {type.universes && <FanficSection type={type} source={source} details={d} corrections={corrections} />}
      {ordered.length > 0 && (
        <FieldGroup title={t("WorkForm.content")}>
          {ordered.map((f) => (
            <FacetLine key={f.facet} facet={f} item={item} source={source} corrections={corrections} suggested={suggestions.data?.find((s) => s.facet === f.facet)?.suggested} />
          ))}
        </FieldGroup>
      )}
      <WorkFields type={type} details={d} corrections={corrections} />
    </div>
  );
}
