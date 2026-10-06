import { CheckIcon, XIcon } from "lucide-react";
import type { FacetSuggestions, SuggestedValue } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n";

/** ✓ and ✗ of a suggestion: the answer is a correction of the facet, after which it is no longer listed. */
function Answers({ yes, no, onAnswer }: { yes: string; no: string; onAnswer: (added: boolean) => Promise<unknown> }) {
  return (
    <>
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className="size-4 rounded-sm text-yes"
        title={yes}
        onClick={() => onAnswer(true)}
        icon={<CheckIcon className="size-3" />}
      />
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className="size-4 rounded-sm text-destructive"
        title={no}
        onClick={() => onAnswer(false)}
        icon={<XIcon className="size-3" />}
      />
    </>
  );
}

/**
 * The values the application suggests adding, facet by facet, the likeliest first: each one
 * confirmed (it is added to the work) or rejected (it is taken away and not suggested again).
 */
export function SuggestedValues({ suggestions, corrections }: { suggestions: FacetSuggestions[]; corrections: Corrections }) {
  const shown = suggestions.filter((s) => s.suggested.length > 0);
  if (shown.length === 0) return null;
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-dashed p-2">
      <div className="text-xs text-muted-foreground">Подсказки: похоже, что у работы есть и это</div>
      {shown.map((s) => (
        <div key={s.facet}>
          <div className="mb-1 text-xs text-muted-foreground">{s.label}</div>
          <div className="flex flex-wrap gap-1">
            {s.suggested.map((v) => (
              <SuggestedChip key={v.key} facet={s.facet} value={v} corrections={corrections} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function SuggestedChip({ facet, value, corrections }: { facet: string; value: SuggestedValue; corrections: Corrections }) {
  return (
    <Badge variant="outline" className="border-dashed pr-0.5 font-normal" title={`Вероятность ${percent(value.chance)}`}>
      {value.name}
      <span className="text-muted-foreground tabular-nums">{percent(value.chance)}</span>
      <Answers
        yes="Да, это есть у работы"
        no="Нет, этого у работы нет"
        onAnswer={(added) => corrections.setFacet(facet, { key: value.key }, added)}
      />
    </Badge>
  );
}

/**
 * A site's value that does not look like the work: a "?" that says how unlikely it is, with ✓ to
 * keep it and ✗ to take it away. Goes inside the value's chip.
 */
export function DoubtMark({ facet, value, corrections }: { facet: string; value: SuggestedValue; corrections: Corrections }) {
  return (
    <>
      <Tooltip>
        <TooltipTrigger render={<span className="cursor-help font-semibold text-maybe" />}>?</TooltipTrigger>
        <TooltipContent>Не похоже на эту работу ({percent(value.chance)})</TooltipContent>
      </Tooltip>
      <Answers
        yes="Оставить: значение сайта верно"
        no="Убрать: значение сайта неверно"
        onAnswer={(added) => corrections.setFacet(facet, { key: value.key }, added)}
      />
    </>
  );
}
