import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, PercentIcon, SplitIcon, Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type AuthorOffer, type AuthorSaid, type AuthorWork, type ContentTypeInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { percent } from "@/i18n/format";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { cn } from "@/lib/utils";
import { AuthorHead, authorsKey, WorkLink } from "./pieces";

/** How many pairs a page shows, and how many more each "more" adds. */
const PAGE = 30;

/** How many pairs of works an offer shows before they are asked for: the strongest, enough to judge by. */
const PAIRS_SHOWN = 5;

type Verdict = "SAME" | "DIFFERENT" | "PARTIAL" | "NONE";

/** The author of a site an answer names a side by: theirs whatever the normalized authors merged since the row was read. */
const accountOf = ({ sites: [site] }: AuthorOffer["first"]) => ({ source: site.source, author: site.author });

/** A row: a pair of authors. */
const rowOf = (o: AuthorOffer) => `${o.first.id}|${o.second.id}`;

/** A row read again the same: what tells of it. */
const sameRow = (a: AuthorOffer, b: AuthorOffer) =>
  a.matching === b.matching &&
  Math.round(a.expected * 10) === Math.round(b.expected * 10) &&
  a.said === b.said &&
  a.oneEntry === b.oneEntry &&
  a.first.entries.length === b.first.entries.length &&
  a.second.entries.length === b.second.entries.length;

const noneWord = { OFFERED: "Offers.none", DIFFERENT: "Offers.noneDifferent", PARTIAL: "Offers.nonePartial", ALL: "Offers.noneOfAuthor" } as const;

/**
 * The pairs of authors by what was [said] of them, of one [author] when given. On a tab of the screen
 * a row answered on leaves at once, nothing read again for it. In an author's card, where the pair
 * stays with its word, the answer reads every list of the authors again; [onMerged] is told of a merge:
 * the author the card is of may be no more.
 */
export function Offers({
  type,
  said,
  author,
  every,
  onOpen,
  onMerged,
}: {
  type: ContentTypeInfo;
  said: AuthorSaid;
  author?: number;
  every: number;
  onOpen: (id: number) => void;
  onMerged?: () => void;
}) {
  const { t } = useTranslation("author");
  const queryClient = useQueryClient();
  const [limit, setLimit] = useState(PAGE);
  const list = useQuery({
    queryKey: [...authorsKey(type.id), "offers", said, author, limit],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/authors/offers", { params: { path: { type: type.id }, query: { said, author, offset: 0, limit } } })),
    refetchInterval: every || false,
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.offers, rowOf, sameRow);
  const offers = calm.shown ?? [];
  const answer = async (o: AuthorOffer, verdict: Verdict) => {
    ensureOk(await api.PUT("/api/authors/answer", { body: { first: accountOf(o.first), second: accountOf(o.second), verdict } }));
    if (verdict === "SAME") onMerged?.();
    if (author === undefined) calm.remove([rowOf(o)]);
    else await queryClient.invalidateQueries({ queryKey: authorsKey(type.id) });
  };
  return (
    <div className="flex flex-col gap-3">
      {data && <span className="text-sm text-muted-foreground tabular-nums">{t("Offers.total", { count: data.total })}</span>}
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("Offers.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("Offers.failed", { error: list.error.message })}</p>}
      {data && offers.length === 0 && <p className="text-sm text-muted-foreground">{t(noneWord[said])}</p>}
      {offers.length > 0 && (
        <ul className={cn("flex flex-col gap-3", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
          {offers.map((o) => (
            <OfferRow key={rowOf(o)} rowRef={calm.rowRef(rowOf(o))} locked={calm.locked} offer={o} onOpen={onOpen} answer={(verdict) => answer(o, verdict)} />
          ))}
        </ul>
      )}
      {data && data.total > offers.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("Offers.more", { count: Math.min(PAGE, data.total - offers.length) })}
        </Button>
      )}
    </div>
  );
}

/** Two authors side by side, the answers under them at one place of every row, then the works that tell. */
function OfferRow({
  offer: o,
  rowRef,
  locked,
  onOpen,
  answer,
}: {
  offer: AuthorOffer;
  rowRef: (e: HTMLElement | null) => void;
  /** The rows moving: the buttons shown disabled, the reason no press is taken. */
  locked: boolean;
  onOpen: (id: number) => void;
  answer: (verdict: Verdict) => Promise<void>;
}) {
  const { t } = useTranslation("author");
  const [allPairs, setAllPairs] = useState(false);
  const pairs = allPairs ? o.pairs : o.pairs.slice(0, PAIRS_SHOWN);
  return (
    <li ref={rowRef} className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="grid gap-x-4 gap-y-2 md:grid-cols-2">
        <AuthorHead author={o.first} onOpen={onOpen} />
        <AuthorHead author={o.second} onOpen={onOpen} />
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-y py-1.5 text-xs text-muted-foreground">
        {o.oneEntry && (
          <span className="font-semibold text-foreground" title={t("OfferRow.oneEntryHint")}>
            {t("OfferRow.oneEntry")}
          </span>
        )}
        <span className="font-semibold text-foreground tabular-nums" title={t("OfferRow.matchingHint")}>
          {t("OfferRow.matching", { count: o.matching })}
        </span>
        <span className="tabular-nums" title={t("OfferRow.expectedHint")}>
          {t("OfferRow.expected", { value: o.expected.toFixed(1) })}
        </span>
        <div className="ml-auto flex flex-wrap items-center gap-1.5">
          {o.said ? (
            <>
              <span className="font-semibold text-foreground">{t(`OfferRow.said.${o.said}`)}</span>
              <AsyncButton size="sm" variant="ghost" icon={<Undo2Icon />} disabled={locked} title={t("OfferRow.takeBackHint")} onClick={() => answer("NONE")}>
                {t("OfferRow.takeBack")}
              </AsyncButton>
            </>
          ) : (
            <>
              <AsyncButton size="sm" variant="outline" icon={<CheckIcon />} disabled={locked} title={t("OfferRow.sameHint")} onClick={() => answer("SAME")}>
                {t("OfferRow.same")}
              </AsyncButton>
              <AsyncButton size="sm" variant="outline" icon={<SplitIcon />} disabled={locked} title={t("OfferRow.differentHint")} onClick={() => answer("DIFFERENT")}>
                {t("OfferRow.different")}
              </AsyncButton>
              <AsyncButton size="sm" variant="outline" icon={<PercentIcon />} disabled={locked} title={t("OfferRow.partialHint")} onClick={() => answer("PARTIAL")}>
                {t("OfferRow.partial")}
              </AsyncButton>
            </>
          )}
        </div>
      </div>
      {pairs.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm">
          {pairs.map((p) => (
            <li key={`${p.first.source}/${p.first.item}|${p.second.source}/${p.second.item}`} className="grid items-baseline gap-x-4 md:grid-cols-[1fr_1fr_5.5rem]">
              <WorkLink work={p.first} />
              <WorkLink work={p.second} />
              <span className={cn("text-right text-xs tabular-nums", p.answer ? "font-semibold text-foreground" : "text-muted-foreground")}>
                {p.answer ? t(`OfferRow.answer.${p.answer}`) : percent(p.chance)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {o.pairs.length > PAIRS_SHOWN && (
        <Button variant="ghost" size="xs" className="self-start" onClick={() => setAllPairs(!allPairs)}>
          {allPairs ? t("OfferRow.fewerPairs") : t("OfferRow.allPairs", { count: o.pairs.length })}
        </Button>
      )}
      <div className="grid gap-x-4 md:grid-cols-2">
        <Rest works={o.first.rest} />
        <Rest works={o.second.rest} />
      </div>
    </li>
  );
}

/** The works of an author in no pair, folded. */
function Rest({ works }: { works: AuthorWork[] }) {
  const { t } = useTranslation("author");
  if (works.length === 0) return <div />;
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-xs text-muted-foreground">{t("Rest.more", { count: works.length })}</summary>
      <ul className="mt-1 flex max-h-60 flex-col overflow-y-auto">
        {works.map((w) => (
          <li key={`${w.source}/${w.item}`}>
            <WorkLink work={w} />
          </li>
        ))}
      </ul>
    </details>
  );
}
