import { useContext } from "react";
import { useTranslation } from "react-i18next";
import type { SuggestedValue } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { CHIP_TOUCH, FacetAnswers } from "@/correction/FacetAnswers";
import { ValueActions } from "@/correction/valueActions";
import { ValueMenu } from "@/correction/ValueMenu";
import { cn } from "@/lib/utils";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";
import { valueName } from "@/i18n/label";
import { TagNameLink } from "@/tag/TagNameLink";

/** A value the application suggests, in its field: the answer is a correction of the facet, after which the value is no longer suggested. */
export function SuggestedChip({ facet, value, corrections, tagLink }: { facet: string; value: SuggestedValue; corrections: Corrections; tagLink?: boolean }) {
  const { t } = useTranslation("suggestion");
  const actions = useContext(ValueActions);
  // The contract gives every suggestion a chance; a null one is still not shown as "0%"
  const chance = value.chance == null ? undefined : value.chance;
  return (
    <Badge
      variant="outline"
      className={cn("group border-dashed pr-0.5 font-normal", CHIP_TOUCH)}
      title={[value.description, chance !== undefined ? t("SuggestedChip.chance", { chance: percent(chance) }) : undefined].filter(Boolean).join("\n") || undefined}
    >
      {tagLink ? <TagNameLink tag={value.key}>{valueName(value)}</TagNameLink> : valueName(value)}
      {chance !== undefined && <span className="text-[10px] text-muted-foreground tabular-nums">{percent(chance)}</span>}
      <FacetAnswers facet={facet} value={{ key: value.key }} corrections={corrections} />
      {actions && <ValueMenu asked={{ facet, value: value.key, name: valueName(value), summary: value.description ?? undefined }} corrections={corrections} tag={tagLink === true} />}
    </Badge>
  );
}
