import { CheckIcon, Undo2Icon, XIcon } from "lucide-react";
import type { FacetValueInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { cn } from "@/lib/utils";
import type { Corrections } from "./useCorrections";

const BUTTON = "size-4 rounded-sm";

/**
 * The user's word on a value of a facet, inside its chip: ✓ the work has it, ✕ it has it not. A
 * value the user confirmed shows ✓ pressed, and pressing it takes the word back; one the user
 * added has ✕ that takes it away; one the user took away is crossed out and ↺ brings it back. A
 * suggested value, not the work's yet, has no word on it ([corrected] undefined).
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
  if (value.corrected === "ADDED")
    return <AsyncButton variant="ghost" size="icon-xs" className={BUTTON} title="Убрать добавленное вами" onClick={takeBack} icon={<XIcon className="size-3" />} />;

  const confirmed = value.corrected === "CONFIRMED";
  return (
    <>
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, confirmed ? "bg-yes! text-white! hover:bg-yes/90!" : "text-yes")}
        aria-pressed={confirmed}
        title={confirmed ? "Вы ответили: это у работы есть. Снять ответ" : "Да, это у работы есть"}
        onClick={() => (confirmed ? takeBack() : answer(true))}
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
