import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, HelpCircleIcon, Undo2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type MarkedWork, type MarkVerdict } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { percent } from "@/i18n/format";
import { itemHref } from "@/item/itemHash";
import { ITEMS, itemKey } from "@/item/lists";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/** How many works a page shows, and how many more each "more" adds. */
const PAGE = 50;

/**
 * How often the table is read again till the user chooses ([RefreshSelect]): never — every reading
 * ranks every work of the universes anew, seconds of a large one.
 */
const EVERY_MS = 0;

export const markingKey = (facet: string, value: string) => ["marking", facet, value];

/** A row of the table: a work. */
const rowOf = (w: MarkedWork) => `${w.source}/${w.item}`;

/** A row read again the same: its chance to the shown digits, its answer. */
const sameRow = (a: MarkedWork, b: MarkedWork) => Math.round((a.chance ?? -1) * 100) === Math.round((b.chance ?? -1) * 100) && a.answer === b.answer;

const VERDICTS: { verdict: MarkVerdict; icon: React.ReactNode; word: "yes" | "unknown" | "no" }[] = [
  { verdict: "YES", icon: <CheckIcon />, word: "yes" },
  { verdict: "UNKNOWN", icon: <HelpCircleIcon />, word: "unknown" },
  { verdict: "NO", icon: <XIcon />, word: "no" },
];

/**
 * THE CONFIRMING OF THE APPLICATION'S MARKING of one value of a facet of the layer — a pairing, a
 * character, a tag: the works that may have it, the likeliest first, each with its title (opening
 * the work), its description folded, the site's line of its pairings and characters and the site's
 * tags, and three answers: the work has it (and, of a pairing, its two characters as main ones), it
 * does not say it outright (asked so no more, nothing learnt), it has it not. A row answered on
 * leaves; the answered ones apart, their answer changed or taken back there. Read again as often as
 * chosen and shown calmly ([useCalmUpdates]).
 */
export function MarkingTable({
  facet,
  value,
  onAnswered,
  className,
}: {
  facet: string;
  value: string;
  /** An answer kept: what the screen shows of the value's answers read anew (the counts of a pairing). */
  onAnswered?: () => void;
  className?: string;
}) {
  const { t } = useTranslation("marking");
  const [answered, setAnswered] = useState(false);
  const [limit, setLimit] = useState(PAGE);
  const [every, setEvery] = useRefresh("marking.refresh", EVERY_MS);
  const queryClient = useQueryClient();
  const list = useQuery({
    queryKey: [...markingKey(facet, value), answered, limit],
    queryFn: async () => unwrap(await api.GET("/api/marking/works", { params: { query: { facet, value, answered, offset: 0, limit } } })),
    refetchInterval: every || false,
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.works, rowOf, sameRow);
  const shown = calm.shown ?? [];
  /** What else shows the works' values read anew: their dialogs, their cards, what the screen counts. */
  const changed = (w: MarkedWork) => {
    queryClient.invalidateQueries({ queryKey: itemKey(w) });
    queryClient.invalidateQueries({ queryKey: [ITEMS] });
    onAnswered?.();
  };
  /** The user's word on a work: once kept, the row leaves — of the answered ones, it shows the new word. */
  const answer = async (w: MarkedWork, verdict: MarkVerdict) => {
    unwrap(await api.PUT("/api/marking/answer", { body: { source: w.source, item: w.item, facet, value, verdict } }));
    if (answered) queryClient.invalidateQueries({ queryKey: markingKey(facet, value) });
    else calm.remove([rowOf(w)]);
    changed(w);
  };
  /** The user's word taken back: once kept, the row leaves the answered ones. */
  const unanswer = async (w: MarkedWork) => {
    unwrap(await api.DELETE("/api/marking/answer", { params: { query: { source: w.source, item: w.item, facet, value } } }));
    calm.remove([rowOf(w)]);
    changed(w);
  };
  return (
    <div className={cn("flex min-h-0 flex-col gap-3", className)}>
      <div className="flex flex-wrap items-center gap-2">
        <Tabs value={answered ? "answered" : "open"} onValueChange={(v) => (setAnswered(v === "answered"), setLimit(PAGE))}>
          <TabsList>
            <TabsTrigger value="open">{t("MarkingTable.open")}</TabsTrigger>
            <TabsTrigger value="answered">{t("MarkingTable.answered")}</TabsTrigger>
          </TabsList>
        </Tabs>
        {data && <span className="text-sm text-muted-foreground tabular-nums">{t("MarkingTable.total", { count: data.total })}</span>}
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </div>
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("MarkingTable.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("MarkingTable.failed", { error: list.error.message })}</p>}
      {data && shown.length === 0 && <p className="text-sm text-muted-foreground">{t(answered ? "MarkingTable.noneAnswered" : "MarkingTable.none")}</p>}
      {shown.length > 0 && (
        <ul className={cn("flex flex-col gap-2", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
          {shown.map((w) => (
            <WorkRow
              key={rowOf(w)}
              rowRef={calm.rowRef(rowOf(w))}
              locked={calm.locked}
              work={w}
              answer={(verdict) => answer(w, verdict)}
              unanswer={() => unanswer(w)}
            />
          ))}
        </ul>
      )}
      {data && data.total > shown.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("MarkingTable.more", { count: Math.min(PAGE, data.total - shown.length) })}
        </Button>
      )}
    </div>
  );
}

/**
 * A work of the table: its title opening the work, the model's chance, the site's line of its
 * pairings and characters, its description folded to three lines till pressed, the site's tags, and
 * the answers — the one given marked, of a work answered on, with the taking back beside.
 */
function WorkRow({
  work: w,
  rowRef,
  locked,
  answer,
  unanswer,
}: {
  work: MarkedWork;
  rowRef: (e: HTMLElement | null) => void;
  /** The table's rows moving: its buttons shown disabled, the reason no press is taken. */
  locked: boolean;
  answer: (verdict: MarkVerdict) => Promise<void>;
  unanswer: () => Promise<void>;
}) {
  const { t } = useTranslation("marking");
  const [whole, setWhole] = useState(false);
  return (
    <li ref={rowRef} className="flex flex-col gap-1.5 rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <a href={itemHref({ source: w.source, item: w.item })} className="font-semibold underline-offset-2 hover:underline">
          {w.title}
        </a>
        <span className="text-xs text-muted-foreground">{w.source}</span>
        {w.chance != null && <span className="text-xs text-muted-foreground tabular-nums">{percent(w.chance)}</span>}
        <div className="ml-auto flex flex-wrap gap-1.5">
          {VERDICTS.map(({ verdict, icon, word }) => (
            <AsyncButton
              key={verdict}
              size="sm"
              variant={w.answer === verdict ? "default" : "outline"}
              icon={icon}
              disabled={locked || w.answer === verdict}
              title={t(`MarkingTable.${word}Hint`)}
              onClick={() => answer(verdict)}
            >
              {t(`MarkingTable.${word}`)}
            </AsyncButton>
          ))}
          {w.answer && (
            <AsyncButton size="sm" variant="ghost" icon={<Undo2Icon />} disabled={locked} title={t("MarkingTable.unanswerHint")} onClick={unanswer}>
              {t("MarkingTable.unanswer")}
            </AsyncButton>
          )}
        </div>
      </div>
      {w.line && (
        <div className="text-sm">
          <span className="text-muted-foreground">{t("MarkingTable.line")}: </span>
          {w.line}
        </div>
      )}
      {w.description && (
        // Text to select and copy: unfolded or folded by a press that selects nothing
        <div
          className={cn("cursor-pointer text-sm whitespace-pre-line select-text text-muted-foreground", !whole && "line-clamp-3")}
          title={t(whole ? "MarkingTable.fold" : "MarkingTable.unfold")}
          onClick={() => {
            if (!window.getSelection()?.toString()) setWhole(!whole);
          }}
        >
          {w.description}
        </div>
      )}
      {w.tags.length > 0 && (
        <div className="text-xs">
          <span className="text-muted-foreground">{t("MarkingTable.tags")}: </span>
          {w.tags.join(", ")}
        </div>
      )}
    </li>
  );
}
