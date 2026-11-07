import { CheckIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AsyncButton } from "@/components/AsyncButton";
import type { Corrections } from "@/correction/useCorrections";

/** Which values a "No other …" button is about: it picks the button's text and hints. */
type AllNamedKind = "universes" | "mainCharacters" | "secondaryCharacters" | "pairings";

/**
 * "No other …": the user's word that the work's values of the facet are all named — the others
 * are taken from it, not offered and rejected for the model; pressed again, taken back.
 */
export function AllNamed({
  facet,
  named,
  complete,
  corrections,
}: {
  facet: string;
  /** What the values are, for the button's text and hints: "No other universes". */
  named: AllNamedKind;
  complete: boolean;
  corrections: Corrections;
}) {
  const { t } = useTranslation("universe");
  return (
    <AsyncButton
      size="sm"
      variant={complete ? "secondary" : "ghost"}
      aria-pressed={complete}
      onClick={() => corrections.setComplete(facet, !complete)}
      icon={complete ? <CheckIcon /> : undefined}
      title={complete ? t(`AllNamed.${named}CompleteHint`) : t(`AllNamed.${named}OpenHint`)}
    >
      {t(`AllNamed.${named}`)}
    </AsyncButton>
  );
}
