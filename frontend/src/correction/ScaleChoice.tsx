import { useTranslation } from "react-i18next";
import type { ItemFacet } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { percent } from "@/i18n/format";
import { label, sourceShort } from "@/i18n/label";
import { cn } from "@/lib/utils";
import type { Corrections } from "./useCorrections";

/**
 * A FACET WHOSE VALUES ARE A SCALE — the age rating — as the scale: every step in its order, the
 * work's one chosen, and where it is from — the user's answer, the site's word, the model's chance
 * by the tags; under every other step the model's chance of it; a step the site lets not faded, to
 * choose all the same where the site is wrong. Sites that disagree choose nothing: every step held
 * is marked with its sites, and the user decides. A step pressed is the work's — the user's answer —
 * the user's own pressed again taken back.
 */
export function ScaleChoice({ facet: f, corrections }: { facet: ItemFacet; corrections: Corrections }) {
  const { t } = useTranslation("correction");
  const steps = f.steps ?? [];
  const held = f.values.filter((v) => v.corrected !== "REMOVED" && !v.offered);
  const answered = held.find((v) => v.corrected === "ADDED" || v.corrected === "CONFIRMED");
  // Every step a site names, with the sites naming it: one site naming two is no disagreement
  const named = new Map(steps.map((s) => [s.key, (f.said ?? []).filter((x) => x.words.some((w) => w.key === s.key)).map((x) => x.source)]));
  const naming = (f.said ?? []).filter((x) => x.words.some((w) => named.has(w.key)));
  const disagree = !answered && (held.length > 1 || (naming.length > 1 && [...named.values()].filter((sites) => sites.length > 0).length > 1));
  const current = answered ?? (disagree ? undefined : held[0]);
  const mine = answered !== undefined;
  const origin = disagree
    ? t("ScaleChoice.disagree")
    : !current
      ? t("ScaleChoice.none")
      : mine
        ? t("ScaleChoice.mine")
        : current.fromSite
          ? t("ScaleChoice.site")
          : t("ScaleChoice.model", { chance: current.chance != null ? percent(current.chance) : "—" });
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-start gap-1">
        {steps.map((s) => {
          const chosen = s.key === current?.key;
          const name = s.nameKey ? label(s.nameKey) : s.name;
          const of = disagree ? named.get(s.key) : undefined;
          const heldOne = of !== undefined && of.length > 0;
          return (
            <div key={s.key} className="flex flex-col items-center gap-0.5">
              <AsyncButton
                size="xs"
                variant={chosen ? "default" : heldOne ? "secondary" : "outline"}
                className={cn("min-w-12 pointer-coarse:h-11", !s.allowed && !chosen && "opacity-50")}
                aria-pressed={chosen}
                title={
                  chosen && mine
                    ? t("ScaleChoice.takeBack")
                    : s.allowed
                      ? t("ScaleChoice.choose", { name })
                      : t("ScaleChoice.chooseAgainstSite", { name })
                }
                onClick={() => (chosen && mine ? corrections.resetFacet(f.facet, s.key) : corrections.setFacet(f.facet, { key: s.key }, true))}
              >
                {name}
              </AsyncButton>
              <span className="text-[10px] text-muted-foreground tabular-nums">
                {heldOne ? of.map(sourceShort).join(", ") : s.chance != null ? percent(s.chance) : " "}
              </span>
            </div>
          );
        })}
      </div>
      <span className="text-xs text-muted-foreground">{origin}</span>
    </div>
  );
}
