import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLinkIcon, Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, refKey, sameItem, unwrap, type ContentTypeInfo, type DuplicatePair, type ItemRef, type WorkRelation, type WorkSite } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemKey } from "@/item/lists";

type Verdict = NonNullable<DuplicatePair["answer"]>;

const VERDICTS: { verdict: Verdict; word: "same" | "different" | "translation" | "partial" }[] = [
  { verdict: "SAME", word: "same" },
  { verdict: "DIFFERENT", word: "different" },
  { verdict: "TRANSLATION", word: "translation" },
  { verdict: "PARTIAL", word: "partial" },
];

/** How many pairs are shown at first and by each press for more: a work may be the likeliest of hundreds of others, none hidden. */
const STEP = 10;

const duplicatesKey = (item: ItemRef) => ["itemDuplicates", item.source, item.item];

const ref = (w: ItemRef): ItemRef => ({ source: w.source, item: w.item });

/**
 * What the search of the duplicates found for this work, of any item of it, the likeliest first, to
 * answer here as on the screen of the duplicates. "One work" links them as the work's own search does.
 * An answer is written and shown once kept: nothing is read for it.
 */
export function WorkDuplicates({ type, item, sites, corrections }: { type: ContentTypeInfo; item: ItemRef; sites: WorkSite[]; corrections: Corrections }) {
  const { t } = useTranslation("duplicate");
  const queryClient = useQueryClient();
  const [count, setCount] = useState(STEP);
  const pairs = useQuery({
    queryKey: duplicatesKey(item),
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}/duplicates", { params: { path: item } })),
  });
  if (pairs.isLoading) return <Skeleton className="h-16 w-full" />;
  if (!pairs.data || pairs.data.length === 0) return null;
  const answered = (p: DuplicatePair, answer: Verdict | undefined) =>
    queryClient.setQueryData<DuplicatePair[]>(duplicatesKey(item), (all) =>
      all?.map((q) => (sameItem(q.first, p.first) && sameItem(q.second, p.second) ? { ...q, answer } : q)),
    );
  const answer = async (p: DuplicatePair, other: ItemRef, verdict: Verdict) => {
    if (verdict === "SAME") await corrections.link(other);
    else ensureOk(await api.PUT("/api/types/{type}/duplicates/answer", { params: { path: { type: type.id } }, body: { first: ref(p.first), second: ref(p.second), verdict } }));
    answered(p, verdict);
  };
  const unanswer = async (p: DuplicatePair) => {
    ensureOk(
      await api.DELETE("/api/types/{type}/duplicates/answer", {
        params: { path: { type: type.id }, query: { source: p.first.source, item: p.first.item, otherSource: p.second.source, otherItem: p.second.item } },
      }),
    );
    answered(p, undefined);
  };
  return (
    <ul className="flex flex-col gap-2 text-sm">
      {pairs.data.slice(0, count).map((p) => {
        // The side of the pair that is not of this work
        const other = sites.some((s) => sameItem(s, p.first)) ? p.second : p.first;
        return (
          <li key={refKey(other)} className="flex flex-col gap-1 rounded-lg border p-2">
            <div className="flex items-baseline gap-2">
              <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(other.source)}>
                {sourceShort(other.source)}
              </span>
              <a href={other.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 underline-offset-2 hover:underline">
                {other.title}
                <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
              </a>
              {p.chance != null && <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{percent(p.chance)}</span>}
            </div>
            <div className="flex flex-wrap gap-1">
              {VERDICTS.map(({ verdict, word }) => (
                <AsyncButton
                  key={verdict}
                  size="xs"
                  variant={p.answer === verdict ? "default" : "outline"}
                  disabled={p.answer === verdict}
                  title={t(`PairRow.${word}Hint`)}
                  onClick={() => answer(p, ref(other), verdict)}
                >
                  {t(`PairRow.${word}`)}
                </AsyncButton>
              ))}
              {p.answer && (
                <AsyncButton size="xs" variant="ghost" icon={<Undo2Icon />} title={t("PairRow.unanswerHint")} onClick={() => unanswer(p)}>
                  {t("PairRow.unanswer")}
                </AsyncButton>
              )}
            </div>
          </li>
        );
      })}
      {pairs.data.length > count && (
        <li>
          <Button variant="ghost" size="xs" onClick={() => setCount(count + STEP)}>
            {t("WorkDuplicates.more", { shown: count, total: pairs.data.length })}
          </Button>
        </li>
      )}
    </ul>
  );
}

/** The works said to be related to this one, each word taken back by ↺: the work read anew, its list here being of it. */
export function WorkRelations({ type, item, relations }: { type: ContentTypeInfo; item: ItemRef; relations: WorkRelation[] }) {
  const { t } = useTranslation("duplicate");
  const queryClient = useQueryClient();
  if (relations.length === 0) return null;
  const takeBack = async (r: WorkRelation) => {
    ensureOk(
      await api.DELETE("/api/types/{type}/duplicates/answer", {
        params: { path: { type: type.id }, query: { source: item.source, item: item.item, otherSource: r.source, otherItem: r.item } },
      }),
    );
    queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {relations.map((r) => (
        <li key={refKey(r)} className="flex items-baseline gap-2">
          <span className="shrink-0 text-xs text-muted-foreground">{t(r.kind === "TRANSLATION" ? "PairRow.translation" : "PairRow.partial")}</span>
          <span className="shrink-0 text-xs text-muted-foreground" title={sourceTitle(r.source)}>
            {sourceShort(r.source)}
          </span>
          <a href={r.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 underline-offset-2 hover:underline">
            {r.title}
          </a>
          <AsyncButton size="icon-xs" variant="ghost" icon={<Undo2Icon />} title={t("PairRow.unanswerHint")} onClick={() => takeBack(r)} />
        </li>
      ))}
    </ul>
  );
}
