import { useEffect, useState } from "react";
import { BookOpenIcon, Loader2Icon, OrbitIcon, SearchIcon, UsersIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, UniverseInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { usePref } from "@/lib/usePref";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { CatalogueSearch } from "./CatalogueSearch";
import { UniverseCard } from "./UniverseCard";
import { useUniverses } from "./universeQueries";

/** How many universes the list shows, and adds by "more". */
const PAGE = 100;

/** How long the typing pauses before the list is searched. */
const SEARCH_SETTLE_MS = 300;

/** Of a filter: whatever, with it, without it. */
type Has = "any" | "yes" | "no";

const HAS: Has[] = ["any", "yes", "no"];

/** The filters of the list: bound to an entry of the catalogue, its characters read. */
type Filters = { entries: Has; characters: Has };

const lets = (has: Has, value: boolean) => has === "any" || (has === "yes") === value;

/**
 * THE SCREEN OF THE UNIVERSES — the normalized fandoms — of a content type whose works may be fan
 * fiction: every fandom of every site is bound to one, at first to the one of its name; the user
 * merges those that are one, takes a site's fandom out, puts one under another, binds a universe to
 * entries of the catalogue and has their characters read. The list on the left, the universe opened
 * on the right ("/universes/<id>"), as the tags are.
 */
export function UniversesScreen({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const { t } = useTranslation("universe");
  const universes = useUniverses(type.id);
  return (
    <div className="mx-auto grid max-w-[1800px] grid-cols-[minmax(0,1fr)] gap-4 px-4 py-4 lg:grid-cols-[minmax(0,28rem)_minmax(0,1fr)]">
      <div className={cn("flex flex-col gap-4", selected && "hidden lg:flex")}>
        <UniverseList universes={universes.data} loading={universes.isFetching} error={universes.error?.message} selected={selected} />
      </div>
      {selected ? (
        <UniverseCard key={selected} type={type} value={selected} dictionary={universes.data} />
      ) : (
        <div className="flex flex-col gap-3 text-sm">
          <h1 className="flex items-center gap-2 text-xl font-semibold">
            <OrbitIcon className="size-5" /> {t("UniversesScreen.title")}
          </h1>
          <p className="text-muted-foreground">{t("UniversesScreen.description")}</p>
          <h2 className="font-semibold">{t("UniversesScreen.addFromWikidata")}</h2>
          <p className="text-muted-foreground">{t("UniversesScreen.addFromWikidataHint")}</p>
          <CatalogueSearch type={type} dictionary={universes.data} />
        </div>
      )}
    </div>
  );
}

/** The list of the universes, of the most works first, searched by any of their names and filtered. */
function UniverseList({
  universes,
  loading,
  error,
  selected,
}: {
  universes?: UniverseInfo[];
  loading: boolean;
  error?: string;
  selected?: string;
}) {
  const { t } = useTranslation("universe");
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(PAGE);
  const [filters, setFilters] = usePref<Filters>("universes.filters", { entries: "any", characters: "any" });
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input.trim().toLocaleLowerCase());
      setLimit(PAGE);
    }, SEARCH_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [input]);
  const named = (u: UniverseInfo) =>
    query === "" || [u.name, ...u.sites.map((s) => s.name), ...u.entries.map((e) => e.name)].some((n) => n.toLocaleLowerCase().includes(query));
  const shown = (universes ?? []).filter((u) => named(u) && lets(filters.entries, u.entries.length > 0) && lets(filters.characters, u.characters));
  const filter = (key: keyof Filters, label: string) => (
    <div className="flex items-center gap-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <Tabs
        value={filters[key]}
        onValueChange={(v) => {
          setFilters({ ...filters, [key]: v as Has });
          setLimit(PAGE);
        }}
      >
        <TabsList>
          {HAS.map((h) => (
            <TabsTrigger key={h} value={h}>
              {t(`UniverseList.has.${h}`)}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
    </div>
  );
  return (
    <section className="flex flex-col gap-2 text-sm">
      <h2 className="flex items-center gap-2 font-semibold">
        <a href={screenHref({ kind: "universes" })} onClick={(e) => followLink(e, { kind: "universes" })} className="hover:underline">
          {t("UniverseList.title")}
        </a>
        {universes && <span className="font-normal text-muted-foreground tabular-nums">{shown.length}</span>}
        {loading && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
      </h2>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t("UniverseList.search")} className="pl-8" type="search" />
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-2">
        {filter("entries", t("UniverseList.withEntries"))}
        {filter("characters", t("UniverseList.withCharacters"))}
      </div>
      {error && <p className="text-destructive">{t("UniverseList.loadFailed", { error })}</p>}
      {universes && shown.length === 0 && <p className="text-muted-foreground">{t("UniverseList.empty")}</p>}
      <ul className="flex flex-col divide-y rounded-lg border">
        {shown.slice(0, limit).map((u) => (
          <li key={u.value}>
            <a
              href={screenHref({ kind: "universes", universe: u.value })}
              onClick={(e) => followLink(e, { kind: "universes", universe: u.value })}
              className={cn("flex flex-wrap items-baseline gap-x-2 px-3 py-1.5 hover:bg-muted/50", u.value === selected && "bg-muted")}
            >
              <span className="min-w-0 font-medium">{u.name}</span>
              {u.entries.length > 0 && <BookOpenIcon className="size-3.5 self-center text-muted-foreground" aria-label={t("UniverseList.boundHint")} />}
              {u.characters && <UsersIcon className="size-3.5 self-center text-muted-foreground" aria-label={t("UniverseList.charactersHint")} />}
              <span className="ml-auto flex flex-wrap gap-1 text-xs text-muted-foreground">
                {[...new Set(u.sites.map((s) => s.source))].map((s) => (
                  <span key={s} className="rounded bg-muted px-1" title={sourceTitle(s)}>
                    {sourceShort(s)}
                  </span>
                ))}
                <span className="tabular-nums">{t("UniverseList.works", { count: u.works })}</span>
              </span>
            </a>
          </li>
        ))}
      </ul>
      {shown.length > limit && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + PAGE)}>
          {t("UniverseList.more", { n: Math.min(PAGE, shown.length - limit), left: shown.length - limit })}
        </Button>
      )}
    </section>
  );
}
