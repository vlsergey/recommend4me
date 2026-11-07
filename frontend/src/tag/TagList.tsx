import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CombineIcon, Loader2Icon, SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { IgnoreButton, type IgnoredFilter, IgnoredSelect, ignoredParam } from "./IgnoreTag";
import { QUERY_SETTLE_MS, TAGS } from "./tagQueries";
import { SiteNames, TagNames } from "./TagNames";

/** How many tags a page of the list shows, and adds by "more". */
const PAGE = 100;

/** Whether a tag of the list must come from a site or a catalogue — used on it, bound to an entry of it — must not, or either. */
const SOURCE_FILTERS = ["any", "with", "without"] as const;
type SourceFilter = (typeof SOURCE_FILTERS)[number];

/** The list of the tags, searched by their names and filtered by their sites and by being ignored, a page at a time. */
export function TagList({ selected }: { selected?: string }) {
  const { t } = useTranslation("tag");
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(PAGE);
  const [bySource, setBySource] = useState<Record<string, SourceFilter>>({});
  const [ignored, setIgnored] = useState<IgnoredFilter>("any");
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input.trim());
      setLimit(PAGE);
    }, QUERY_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [input]);
  const chosen = (filter: SourceFilter) => Object.keys(bySource).filter((s) => bySource[s] === filter).sort();
  const withSources = chosen("with");
  const withoutSources = chosen("without");
  const tags = useQuery({
    queryKey: [TAGS, "list", query, withSources, withoutSources, ignored, limit],
    placeholderData: keepPreviousData,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/tags", {
          params: { query: { query: query || undefined, with: withSources, without: withoutSources, ignored: ignoredParam(ignored), limit } },
        }),
      ),
  });
  const page = tags.data;
  const choose = (source: string, filter: SourceFilter) => {
    setBySource((was) => ({ ...was, [source]: filter }));
    setLimit(PAGE);
  };
  return (
    <section className="flex flex-col gap-2">
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t("TagList.searchPlaceholder")} className="pl-8" type="search" />
      </div>
      {page && (
        <div className="flex flex-wrap gap-x-3 gap-y-1.5">
          <IgnoredSelect
            value={ignored}
            onChange={(f) => {
              setIgnored(f);
              setLimit(PAGE);
            }}
          />
          {page.sources.map((source) => (
            <label key={source} className="flex items-center gap-1.5 text-xs text-muted-foreground" title={t("TagList.sourceFilter", { source: sourceTitle(source) })}>
              {sourceShort(source)}
              <select
                value={bySource[source] ?? "any"}
                onChange={(e) => choose(source, e.target.value as SourceFilter)}
                className={cn(
                  "h-7 rounded-md border border-input bg-transparent px-1.5 text-xs text-foreground outline-none focus-visible:border-ring dark:bg-input/30",
                  (bySource[source] ?? "any") !== "any" && "border-primary",
                )}
              >
                {SOURCE_FILTERS.map((f) => (
                  <option key={f} value={f}>
                    {t(`TagList.source.${f}`)}
                  </option>
                ))}
              </select>
            </label>
          ))}
        </div>
      )}
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {page && <span>{t("TagList.total", { count: page.total })}</span>}
        {tags.isFetching && <Loader2Icon className="size-3.5 animate-spin" />}
      </div>
      {tags.isError && <p className="text-sm text-destructive">{t("TagList.loadError", { error: tags.error.message })}</p>}
      <ul className="flex flex-col divide-y rounded-lg border">
        {page?.tags.map((row) => (
          <li key={row.key} className="flex items-center pr-1.5">
            <a
              href={screenHref({ kind: "tags", tag: row.key })}
              onClick={(e) => followLink(e, { kind: "tags", tag: row.key })}
              aria-current={row.key === selected ? "page" : undefined}
              className={cn("flex min-w-0 flex-1 items-center gap-2 px-3 py-1.5 text-sm hover:bg-muted/50", row.key === selected && "bg-muted", row.ignored && "opacity-60")}
            >
              {/* The names come first: at least half the row, the sites cut before them */}
              <TagNames tag={row} className="min-w-[50%] flex-1" />
              {row.variants.length > 1 && (
                <Badge variant="secondary" className="shrink-0" title={t("TagList.siteTags", { names: row.variants.map((v) => v.name).join(", ") })}>
                  <CombineIcon /> {row.variants.length}
                </Badge>
              )}
              <SiteNames tag={row} className="min-w-0" />
              <span className="w-10 text-right text-xs tabular-nums text-muted-foreground" title={t("TagList.works")}>
                {row.works}
              </span>
            </a>
            <IgnoreButton tag={row} />
          </li>
        ))}
      </ul>
      {page && page.tags.length < page.total && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + PAGE)} disabled={tags.isFetching}>
          {t("TagList.more")}
        </Button>
      )}
    </section>
  );
}
