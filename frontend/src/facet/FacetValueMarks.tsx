import { useTranslation } from "react-i18next";
import type { FacetValueInfo } from "@/api/client";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { percent } from "@/i18n/format";
import { sourceShort } from "@/i18n/label";

/** The facet of the universes: a value of it drafted is the universe of a fandom of the site. */
const UNIVERSE = "universe";

/** Below one half the model thinks the work more likely has the value not than has it. */
const HALF = 0.5;

/**
 * The model's chance is shown only by a value the user has not answered on, and only when the
 * model has one: a value the model has no chance of comes with none — null in the JSON, not only
 * absent — and shows none.
 */
export function shownChance(value: FacetValueInfo): number | undefined {
  // A tag the work has by a tag under it: not the model's word, no chance shown
  return value.corrected || value.implied || value.draftedFrom != null || value.chance == null ? undefined : value.chance;
}

/**
 * In small print by a value drafted from the site's value, the site's value in its title: "by fandom" by a universe — the
 * universe of a fandom of the work's site, nothing to confirm —, "by tag" by any other, drafted through the dictionary.
 */
function DraftedMark({ from, fandom }: { from: string; fandom: boolean }) {
  const { t } = useTranslation("facet");
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="cursor-help text-[10px] text-muted-foreground" />}>
        {t(fandom ? "ChanceMark.draftedByFandom" : "ChanceMark.drafted")}
      </TooltipTrigger>
      <TooltipContent>{t(fandom ? "ChanceMark.draftedByFandomFrom" : "ChanceMark.draftedFrom", { from })}</TooltipContent>
    </Tooltip>
  );
}

/** "site" in small print by a value the site gives the work, the user not having answered on it: told apart from the model's offers. */
function SiteMark({ sites }: { sites?: string[] }) {
  const { t } = useTranslation("facet");
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="cursor-help text-[10px] text-muted-foreground" />}>{t("ChanceMark.fromSite")}</TooltipTrigger>
      <TooltipContent>
        {sites && sites.length > 0 && `${sites.map(sourceShort).join(", ")}. `}
        {t("ChanceMark.fromSiteNote")}
      </TooltipContent>
    </Tooltip>
  );
}

/** The model more likely says no than yes to a value the user has not answered on. */
export function unlikely(value: FacetValueInfo): boolean {
  const chance = shownChance(value);
  return chance !== undefined && chance < HALF;
}

/**
 * "87%" in small print — the model's chance by the value's name; amber, with a word why, when below
 * one half; "site" before it by a value the site gives. A drafted value the user has not answered on
 * says so instead.
 */
export function ChanceMark({ value, facet }: { value: FacetValueInfo; facet: string }) {
  // Null in the JSON for a value not drafted, as the chance: not only absent
  if (value.draftedFrom != null && !value.corrected) return <DraftedMark from={value.draftedFrom} fandom={facet === UNIVERSE} />;
  const site = value.fromSite && !value.corrected && !value.implied;
  return (
    <>
      {site && <SiteMark sites={value.sites} />}
      <Chance value={value} />
    </>
  );
}

function Chance({ value }: { value: FacetValueInfo }) {
  const { t } = useTranslation("facet");
  const chance = shownChance(value);
  if (chance === undefined) return null;
  const text = percent(chance);
  if (!unlikely(value)) return <span className="text-[10px] text-muted-foreground tabular-nums">{text}</span>;
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="cursor-help text-[10px] font-medium text-maybe tabular-nums" />}>{text}</TooltipTrigger>
      <TooltipContent>{t("ChanceMark.unlikely")}</TooltipContent>
    </Tooltip>
  );
}
