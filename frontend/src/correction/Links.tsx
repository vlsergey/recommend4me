import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ExternalLinkIcon, Link2Icon, Loader2Icon, SearchIcon, UnlinkIcon } from "lucide-react";
import { api, refKey, sameItem, unwrap, type ContentTypeInfo, type ItemSummary } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceTitle } from "@/contenttype/types";
import { Cover } from "@/item/pieces";
import type { Corrections } from "./useCorrections";

const FOUND = 8;

/**
 * The other items that are this same work — on another site, or twice on one — and a search to
 * add one: the works of the same content type, picked by a click.
 */
export function Links({ type, summary, corrections }: { type: ContentTypeInfo; summary: ItemSummary; corrections: Corrections }) {
  const [searching, setSearching] = useState(false);
  return (
    <section>
      <div className="mb-2 flex items-center gap-1">
        <h4 className="text-sm font-semibold">Та же работа</h4>
        {!searching && (
          <Button variant="ghost" size="xs" className="text-muted-foreground" onClick={() => setSearching(true)}>
            <Link2Icon /> связать…
          </Button>
        )}
      </div>
      {summary.linked.length === 0 && !searching && (
        <p className="text-xs text-muted-foreground">
          Нашли эту же работу на другом сайте или дубль на этом? Свяжите их: одна карточка, одна оценка.
        </p>
      )}
      <ul className="flex flex-col gap-1 text-sm">
        {summary.linked.map((l) => (
          <li key={refKey(l)} className="flex items-center gap-2">
            <span className="shrink-0 text-xs text-muted-foreground">{sourceTitle(type, l.source)}</span>
            <a href={l.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 truncate underline-offset-2 hover:underline" title={l.title}>
              {l.title}
              <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
            </a>
            <AsyncButton
              variant="ghost"
              size="icon-xs"
              title="Развязать: это разные работы"
              onClick={() => corrections.unlink(l)}
              icon={<UnlinkIcon />}
              className="text-muted-foreground"
            />
          </li>
        ))}
      </ul>
      {searching && <LinkSearch type={type} summary={summary} corrections={corrections} onDone={() => setSearching(false)} />}
    </section>
  );
}

function LinkSearch({
  type,
  summary,
  corrections,
  onDone,
}: {
  type: ContentTypeInfo;
  summary: ItemSummary;
  corrections: Corrections;
  onDone: () => void;
}) {
  const [input, setInput] = useState(summary.title);
  const [search, setSearch] = useState(summary.title);
  useEffect(() => {
    const t = setTimeout(() => setSearch(input.trim()), 300);
    return () => clearTimeout(t);
  }, [input]);

  const found = useQuery({
    queryKey: ["link-search", type.id, search],
    enabled: search !== "",
    queryFn: async () =>
      unwrap(
        await api.GET("/api/types/{type}/items", {
          params: { path: { type: type.id }, query: { view: "ALL", search, limit: FOUND + 1 + summary.linked.length } },
        }),
      ),
  });
  const candidates = (found.data?.items ?? [])
    .filter((s) => !sameItem(s, summary) && !summary.linked.some((l) => sameItem(l, s)))
    .slice(0, FOUND);

  return (
    <div className="mt-2 flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          autoFocus
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && (e.preventDefault(), onDone())}
          placeholder="Название или автор"
          className="h-8 pl-8"
          type="search"
        />
      </div>
      {found.isFetching && candidates.length === 0 && (
        <div className="flex justify-center py-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
        </div>
      )}
      {!found.isFetching && search && candidates.length === 0 && <div className="text-xs text-muted-foreground">Ничего не найдено</div>}
      <ul className="flex flex-col gap-1">
        {candidates.map((s) => (
          <li key={refKey(s)}>
            <AsyncButton
              variant="ghost"
              className="h-auto w-full justify-start gap-2 px-1 py-1 text-left font-normal"
              onClick={async () => {
                await corrections.link(s);
                onDone();
              }}
              title="Связать: это та же работа"
            >
              <Cover typeId={type.id} summary={s} className="aspect-[2/1] w-14 shrink-0 rounded" />
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-sm">{s.title}</span>
                <span className="truncate text-xs text-muted-foreground">
                  {sourceTitle(type, s.source)}
                  {s.version && ` · ${s.version}`}
                  {s.linked.length > 0 && ` · связано: ${s.linked.length}`}
                </span>
              </span>
            </AsyncButton>
          </li>
        ))}
      </ul>
      <Button variant="ghost" size="sm" onClick={onDone} className="self-end">
        Готово
      </Button>
    </div>
  );
}
