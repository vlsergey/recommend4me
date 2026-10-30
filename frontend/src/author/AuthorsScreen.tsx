import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, SplitIcon, Undo2Icon, UsersIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type AuthorOffer, type AuthorSide, type AuthorWork, type ContentTypeInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { percent } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/** How many pairs a page shows, and how many more each "more" adds. */
const PAGE = 30;

/** How often the lists are read again till the user chooses ([RefreshSelect]): never — they change by the user's answers. */
const EVERY_MS = 0;

const authorsKey = (type: string) => ["authors", type];

/** Which list the screen shows: the pairs offered, the authors merged, the pairs told apart. */
type Shown = "offers" | "merged" | "distinct";

/** A row: a pair of authors. */
const rowOf = (o: AuthorOffer) => `${o.first.id}|${o.second.id}`;

/** A row read again the same: what tells of it. */
const sameRow = (a: AuthorOffer, b: AuthorOffer) => a.matching === b.matching && Math.round(a.expected * 10) === Math.round(b.expected * 10);

/**
 * THE SCREEN OF THE NORMALIZED AUTHORS of a content type: the authors of the sites, each at first a
 * writer of their own, merged by the user on the evidence of their works, never of their names — of
 * two authors the works that match by the user's word on the duplicates, those that may, with their
 * chance, and the rest of the works of each: one writer, or different. Those merged and those told
 * apart apart, their word taken back there. Nothing read again for an answer: as often as chosen.
 */
export function AuthorsScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("author");
  const [shown, setShown] = useState<Shown>("offers");
  const [every, setEvery] = useRefresh("authors.refresh", EVERY_MS);
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-4">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <UsersIcon className="size-5" /> {t("AuthorsScreen.title")}
        </h1>
        <p className="text-sm text-muted-foreground">{t("AuthorsScreen.description")}</p>
      </header>
      <div className="flex flex-wrap items-center gap-2">
        <Tabs value={shown} onValueChange={(v) => setShown(v as Shown)}>
          <TabsList>
            <TabsTrigger value="offers">{t("AuthorsScreen.offers")}</TabsTrigger>
            <TabsTrigger value="merged">{t("AuthorsScreen.merged")}</TabsTrigger>
            <TabsTrigger value="distinct">{t("AuthorsScreen.distinct")}</TabsTrigger>
          </TabsList>
        </Tabs>
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </div>
      {shown === "merged" ? <Merged key="merged" type={type} every={every} /> : <Offers key={shown} type={type} distinct={shown === "distinct"} every={every} />}
    </div>
  );
}

/** The pairs of authors offered, or told apart: each answered on, and leaving once the word is kept. */
function Offers({ type, distinct, every }: { type: ContentTypeInfo; distinct: boolean; every: number }) {
  const { t } = useTranslation("author");
  const [limit, setLimit] = useState(PAGE);
  const list = useQuery({
    queryKey: [...authorsKey(type.id), "offers", distinct, limit],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/authors/offers", { params: { path: { type: type.id }, query: { distinct, offset: 0, limit } } })),
    refetchInterval: every || false,
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.offers, rowOf, sameRow);
  const offers = calm.shown ?? [];
  const answer = async (o: AuthorOffer, verdict: "SAME" | "DIFFERENT" | "NONE") => {
    ensureOk(await api.PUT("/api/authors/answer", { body: { first: o.first.id, second: o.second.id, verdict } }));
    calm.remove([rowOf(o)]);
  };
  return (
    <div className="flex flex-col gap-3">
      {data && <span className="text-sm text-muted-foreground tabular-nums">{t("Offers.total", { count: data.total })}</span>}
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("Offers.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("Offers.failed", { error: list.error.message })}</p>}
      {data && offers.length === 0 && <p className="text-sm text-muted-foreground">{t(distinct ? "Offers.noneDistinct" : "Offers.none")}</p>}
      {offers.length > 0 && (
        <ul className={cn("flex flex-col gap-2", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
          {offers.map((o) => (
            <OfferRow key={rowOf(o)} rowRef={calm.rowRef(rowOf(o))} locked={calm.locked} offer={o} distinct={distinct} answer={(verdict) => answer(o, verdict)} />
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

/** Two authors: what tells of them, the pairs of their works, the rest of the works of each, and the answers. */
function OfferRow({
  offer: o,
  rowRef,
  locked,
  distinct,
  answer,
}: {
  offer: AuthorOffer;
  rowRef: (e: HTMLElement | null) => void;
  /** The rows moving: the buttons shown disabled, the reason no press is taken. */
  locked: boolean;
  distinct: boolean;
  answer: (verdict: "SAME" | "DIFFERENT" | "NONE") => Promise<void>;
}) {
  const { t } = useTranslation("author");
  return (
    <li ref={rowRef} className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="font-semibold text-foreground tabular-nums" title={t("OfferRow.matchingHint")}>
          {t("OfferRow.matching", { count: o.matching })}
        </span>
        <span className="tabular-nums" title={t("OfferRow.expectedHint")}>
          {t("OfferRow.expected", { value: o.expected.toFixed(1) })}
        </span>
        <div className="ml-auto flex flex-wrap gap-1.5">
          {distinct ? (
            <AsyncButton size="sm" variant="ghost" icon={<Undo2Icon />} disabled={locked} title={t("OfferRow.takeBackHint")} onClick={() => answer("NONE")}>
              {t("OfferRow.takeBack")}
            </AsyncButton>
          ) : (
            <>
              <AsyncButton size="sm" variant="outline" icon={<CheckIcon />} disabled={locked} title={t("OfferRow.sameHint")} onClick={() => answer("SAME")}>
                {t("OfferRow.same")}
              </AsyncButton>
              <AsyncButton size="sm" variant="outline" icon={<SplitIcon />} disabled={locked} title={t("OfferRow.differentHint")} onClick={() => answer("DIFFERENT")}>
                {t("OfferRow.different")}
              </AsyncButton>
            </>
          )}
        </div>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <SideHead side={o.first} />
        <SideHead side={o.second} />
      </div>
      {o.pairs.length > 0 && (
        <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-md border text-sm">
          {o.pairs.map((p) => (
            <li key={`${p.first.source}/${p.first.item}|${p.second.source}/${p.second.item}`} className="grid items-baseline gap-x-3 px-2 py-1 md:grid-cols-[1fr_1fr_auto]">
              <WorkLink work={p.first} />
              <WorkLink work={p.second} />
              <span className={cn("text-xs tabular-nums", p.answer ? "font-semibold text-foreground" : "text-muted-foreground")}>
                {p.answer ? t(`OfferRow.answer.${p.answer}`) : percent(p.chance)}
              </span>
            </li>
          ))}
        </ul>
      )}
      <div className="grid gap-3 md:grid-cols-2">
        <Rest works={o.first.rest} />
        <Rest works={o.second.rest} />
      </div>
    </li>
  );
}

/** An author: the sites' authors they are, each with its works. */
function SideHead({ side, onTakeOut }: { side: AuthorSide; onTakeOut?: (source: string, author: string) => Promise<void> }) {
  const { t } = useTranslation("author");
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {side.sites.map((s) => (
        <span key={`${s.source}/${s.author}`} className="inline-flex items-center gap-1">
          <span className="text-xs text-muted-foreground" title={sourceTitle(s.source)}>
            {sourceShort(s.source)}
          </span>
          <span className="font-semibold">{s.name}</span>
          <span className="text-xs text-muted-foreground tabular-nums" title={t("SideHead.worksHint")}>
            {s.works}
          </span>
          {onTakeOut && (
            <AsyncButton size="icon-xs" variant="ghost" icon={<XIcon />} title={t("SideHead.takeOutHint")} onClick={() => onTakeOut(s.source, s.author)} />
          )}
        </span>
      ))}
    </div>
  );
}

function WorkLink({ work: w }: { work: AuthorWork }) {
  return (
    <span className="min-w-0">
      <span className="mr-1 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
        {sourceShort(w.source)}
      </span>
      <a href={itemHref({ source: w.source, item: w.item })} className="underline-offset-2 hover:underline">
        {w.title}
      </a>
    </span>
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

/** The authors merged: each with the sites' authors they are, one taken out by its ✕. */
function Merged({ type, every }: { type: ContentTypeInfo; every: number }) {
  const { t } = useTranslation("author");
  const queryClient = useQueryClient();
  const key = [...authorsKey(type.id), "merged"];
  const list = useQuery({
    queryKey: key,
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/authors/merged", { params: { path: { type: type.id } } })),
    refetchInterval: every || false,
  });
  const takeOut = async (id: number, source: string, author: string) => {
    ensureOk(await api.DELETE("/api/authors/{id}/sites", { params: { path: { id }, query: { source, author } } }));
    await queryClient.invalidateQueries({ queryKey: key });
  };
  if (list.isLoading) return <p className="text-sm text-muted-foreground">{t("Offers.loading")}</p>;
  if (list.isError) return <p className="text-sm text-destructive">{t("Offers.failed", { error: list.error.message })}</p>;
  const authors = list.data ?? [];
  if (authors.length === 0) return <p className="text-sm text-muted-foreground">{t("Merged.none")}</p>;
  return (
    <ul className="flex flex-col gap-2">
      {authors.map((a) => (
        <li key={a.id} className="flex flex-col gap-1 rounded-lg border p-3">
          <SideHead side={a} onTakeOut={(source, author) => takeOut(a.id, source, author)} />
          <Rest works={a.rest} />
        </li>
      ))}
    </ul>
  );
}
