import { CheckIcon, Undo2Icon, XIcon } from "lucide-react";
import type { FacetValueInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { cn } from "@/lib/utils";
import type { Corrections } from "./useCorrections";

const BUTTON = "size-4 rounded-sm";

/**
 * The user's word on a value of a facet, inside its chip: ✓ the work has it, ✕ it has it not — for
 * a value the user has not answered on, and a suggested one ([corrected] undefined). A value the
 * user confirmed or added says so by its look; the one button it keeps takes the word back, shown
 * when the chip is pointed at. One the user took away is crossed out and ↺ brings it back.
 */
export function FacetAnswers({
  facet,
  value,
  corrections,
}: {
  facet: string;
  value: { key: string; corrected?: FacetValueInfo["corrected"] };
  corrections: Corrections;
}) {
  const answer = (added: boolean) => corrections.setFacet(facet, { key: value.key }, added);
  const takeBack = () => corrections.resetFacet(facet, value.key);

  if (value.corrected === "REMOVED")
    return <AsyncButton variant="ghost" size="icon-xs" className={BUTTON} title="Вернуть: снять ваш ответ" onClick={takeBack} icon={<Undo2Icon className="size-3" />} />;
  if (value.corrected === "ADDED" || value.corrected === "CONFIRMED")
    return (
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100")}
        title={value.corrected === "ADDED" ? "Убрать добавленное вами" : "Снять ваш ответ"}
        onClick={takeBack}
        icon={<XIcon className="size-3" />}
      />
    );

  return (
    <>
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-yes")}
        title="Да, это у работы есть"
        onClick={() => answer(true)}
        icon={<CheckIcon className="size-3" />}
      />
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-destructive")}
        title="Нет, этого у работы нет"
        onClick={() => answer(false)}
        icon={<XIcon className="size-3" />}
      />
    </>
  );
}
