import { CheckIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { FacetValueInfo, ItemFacet, SuggestedValue } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { FieldBlock } from "@/correction/FacetCorrections";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";
import { cn } from "@/lib/utils";
import { ALTERNATIVE_HISTORY, FANFICTION, has, KIND, ORIGINAL, REALISM } from "./fanficFields";

/** The kinds of a work, each with the key of its name in the control. */
const KINDS = [
  { key: FANFICTION, label: "Kind.fanfiction" },
  { key: REALISM, label: "Kind.realism" },
  { key: ALTERNATIVE_HISTORY, label: "Kind.alternativeHistory" },
  { key: ORIGINAL, label: "Kind.original" },
] as const;

/** The user said the work has the value. */
const answeredYes = (v: FacetValueInfo | undefined) => v?.corrected === "ADDED" || v?.corrected === "CONFIRMED";

/**
 * Fan fiction, realism, an alternative history or an original work: four buttons, each with the
 * model's chance of it while the user has not answered, under the sites' words over the group; one the
 * site or the model gave is marked. Pressing a kind confirms it and says no to the others the work has;
 * pressing the chosen one takes the answers back.
 */
export function KindChoice({
  label,
  facet,
  suggested,
  corrections,
}: {
  label: string;
  facet: ItemFacet;
  suggested: SuggestedValue[];
  corrections: Corrections;
}) {
  const { t } = useTranslation("universe");
  const values = facet.values;
  const kindValue = (key: string) => values.find((v) => v.key === key);
  const chosen = KINDS.find((k) => answeredYes(kindValue(k.key)));

  /** The model's chance of the kind: of the work's value, or of the suggestion. */
  const chanceOf = (key: string) => kindValue(key)?.chance ?? suggested.find((x) => x.key === key)?.chance ?? null;

  /** Takes every answer on the kinds back: the site's and the model's word stands again. */
  const takeBack = async () => {
    for (const k of KINDS) if (kindValue(k.key)?.corrected) await corrections.resetFacet(KIND, k.key);
  };

  const choose = async (key: string) => {
    await corrections.setFacet(KIND, { key }, true);
    // A kind only the user gave is taken back; one the site or the model gave is answered no
    for (const other of KINDS.filter((k) => k.key !== key).map((k) => k.key)) {
      const theirs = kindValue(other);
      if (theirs?.corrected === "ADDED") await corrections.resetFacet(KIND, other);
      else if (theirs && has(theirs)) await corrections.setFacet(KIND, { key: other }, false);
    }
  };

  return (
    <FieldBlock field={KIND} label={label} facet={facet} sources={null}>
      <div className="flex flex-wrap gap-1">
        {KINDS.map((k) => {
          const v = kindValue(k.key);
          const mine = chosen?.key === k.key;
          const chance = v?.corrected ? null : chanceOf(k.key);
          return (
            <AsyncButton
              key={k.key}
              size="xs"
              variant={mine ? "default" : v !== undefined && has(v) ? "secondary" : "outline"}
              className={cn("h-auto min-h-6 px-2 pointer-coarse:min-h-11 pointer-coarse:text-sm", v?.corrected === "REMOVED" && "text-muted-foreground line-through")}
              aria-pressed={mine}
              title={mine ? t("KindChoice.chosenHint") : v?.corrected === "REMOVED" ? t("KindChoice.answeredNo") : t("KindChoice.chooseHint")}
              icon={mine ? <CheckIcon /> : undefined}
              onClick={() => (mine ? takeBack() : choose(k.key))}
            >
              {t(k.label)}
              {chance != null && <span className="text-[10px] tabular-nums opacity-70">{percent(chance)}</span>}
            </AsyncButton>
          );
        })}
      </div>
    </FieldBlock>
  );
}
