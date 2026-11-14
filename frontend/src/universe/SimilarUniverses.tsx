import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type SimilarUniverse, type UniverseInfo, type UniverseRelation } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { ButtonGroup } from "@/components/ui/button-group";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { usePref } from "@/lib/usePref";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { SiteFandomName } from "./SiteFandomName";
import { UNIVERSES, useDictionaryChanged } from "./universeQueries";

/** How many alike universes are offered, and how many more each "load more" adds. */
const SIMILAR = 30;

/** How long the typing pauses before the alike are looked for by the text. */
const QUERY_SETTLE_MS = 400;

/** The quiet after the last word before the list is read anew: its order a merge changes. */
const REFRESH_AFTER_MS = 2000;

/** Of a filter: whatever, with it, without it. */
type Has = "any" | "yes" | "no";

const HAS: Has[] = ["any", "yes", "no"];

/** The filters of the alike: in the universe's hierarchy, said a different one. */
type Filters = { hierarchy: Has; different: Has };

/** A filter as the request takes it: none of whatever. */
const asked = (has: Has) => (has === "any" ? undefined : has === "yes");

/** The key of a row: the universe's. */
const rowOf = (s: SimilarUniverse) => s.value;

/** A row read again the same as it is shown: what the row shows unchanged. */
const sameRow = (a: SimilarUniverse, b: SimilarUniverse) =>
  a.name === b.name && a.relation === b.relation && a.similarity === b.similarity && a.works === b.works && JSON.stringify(a.sites) === JSON.stringify(b.sites);

/** The words the user says of another universe, in the order of the buttons. */
const SAID = ["MERGED", "CHILD", "PARENT", "DIFFERENT"] as const;

/**
 * The universes alike this one by the meaning of their sites' fandoms' names — alike a text typed
 * instead — and those bound to an entry it is bound to, offered only: every row has the user's four
 * words on it — the same universe (merged), one under it, one above it, a different one; pressed
 * again, taken back. A word is sent at once and shown pressed once kept; the list, whose order a merge
 * changes, is read anew two seconds after the last press, in the background, and shown calmly
 * ([useCalmUpdates], docs/ui.md): another list, the rows move to it, the buttons locked till a second after.
 * Filtered by whether a universe is in this one's hierarchy and whether it was said a different one (kept in
 * the browser); more read by "load more".
 */
export function SimilarUniverses({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const { t } = useTranslation("universe");
  const queryClient = useQueryClient();
  const changed = useDictionaryChanged(type.id);
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(SIMILAR);
  const [filters, setFilters] = usePref<Filters>("universes.similar.filters", { hierarchy: "any", different: "any" });
  const refresh = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input.trim());
      setLimit(SIMILAR);
    }, QUERY_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [input]);
  useEffect(() => () => clearTimeout(refresh.current), []);
  /** The list as the search and the filters are now, of the [count] likest. */
  const listOf = (count: number) => ({
    queryKey: [UNIVERSES, type.id, "similar", u.value, query, filters, count],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/universes/similar", {
          params: { query: { universe: u.value, query: query || undefined, hierarchy: asked(filters.hierarchy), different: asked(filters.different), limit: count } },
        }),
      ),
  });
  // The rows shown stay while more of them are read
  const similar = useQuery({ ...listOf(limit), placeholderData: keepPreviousData });
  const calm = useCalmUpdates(similar.data, rowOf, sameRow);
  const rows = calm.shown ?? [];
  const filter = (key: keyof Filters, label: string, hint: string) => (
    <div className="flex items-center gap-1.5 text-sm">
      <span className="text-muted-foreground" title={hint}>
        {label}
      </span>
      <Tabs
        value={filters[key]}
        onValueChange={(v) => {
          setFilters({ ...filters, [key]: v as Has });
          setLimit(SIMILAR);
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
  const relate = async (other: SimilarUniverse, word: UniverseRelation) => {
    const relation = other.relation === word ? "NONE" : word;
    ensureOk(await api.PUT("/api/universes/relation", { body: { universe: u.value, other: other.value, relation } }));
    // Kept: the row shows it in its place till the list is read anew
    calm.change((list) => list.map((s) => (s.value === other.value ? { ...s, relation } : s)));
    clearTimeout(refresh.current);
    refresh.current = setTimeout(async () => {
      await queryClient.invalidateQueries({ queryKey: [UNIVERSES, type.id, "similar", u.value] });
      await queryClient.invalidateQueries({ queryKey: [UNIVERSES, type.id, "one", u.value] });
      await changed(true);
    }, REFRESH_AFTER_MS);
  };
  return (
    <section className="flex flex-col gap-2">
      <h2 className="flex items-center gap-2 font-semibold">
        {t("SimilarUniverses.title")}
        {(similar.isFetching || calm.locked) && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
      </h2>
      <p className="text-muted-foreground">{t("SimilarUniverses.hint")}</p>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t("SimilarUniverses.query")} className="pl-8" type="search" />
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-2">
        {filter("hierarchy", t("SimilarUniverses.inHierarchy"), t("SimilarUniverses.inHierarchyHint", { name: u.name }))}
        {filter("different", t("SimilarUniverses.different"), t("SimilarUniverses.differentHint"))}
      </div>
      {/* The words of a row are of the row's universe, said against this one by its name */}
      {rows.length > 0 && <p className="text-xs text-muted-foreground">{t("SimilarUniverses.rowsAre", { name: u.name })}</p>}
      {similar.isError && <p className="text-destructive">{t("SimilarUniverses.loadFailed", { error: similar.error.message })}</p>}
      {/* Locked while the rows move to a list read anew: a press never lands on the row that moved under it */}
      <ul className={cn("flex flex-col divide-y rounded-lg border", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
        {rows.map((s) => {
          const relation = s.relation;
          return (
            <li key={s.value} ref={calm.rowRef(rowOf(s))} className="flex flex-wrap items-center gap-2 px-3 py-1.5">
              <div className="min-w-0 flex-1 basis-56">
                <a
                  href={screenHref({ kind: "universes", universe: s.value })}
                  onClick={(e) => followLink(e, { kind: "universes", universe: s.value })}
                  className="font-medium underline-offset-2 hover:underline"
                >
                  {s.name}
                </a>
                <span className="ml-2 text-xs text-muted-foreground tabular-nums">{Math.round(s.similarity * 100)}%</span>
                <div className="flex flex-wrap gap-1 text-xs text-muted-foreground">
                  {s.sites.map((site) => (
                    <span key={`${site.source}/${site.key}`} title={sourceTitle(site.source)}>
                      {sourceShort(site.source)}: <SiteFandomName site={site} />
                      {site.description && <span> — {site.description}</span>}
                    </span>
                  ))}
                  <span className="tabular-nums">· {t("UniverseCard.works", { count: s.works })}</span>
                  {(relation === "DESCENDANT" || relation === "ANCESTOR") && <span>· {t(`SimilarUniverses.relation.${relation}`)}</span>}
                </div>
              </div>
              <ButtonGroup>
                {SAID.map((word) => (
                  <AsyncButton
                    key={word}
                    size="xs"
                    variant={relation === word ? "secondary" : "outline"}
                    aria-pressed={relation === word}
                    disabled={calm.locked}
                    onClick={() => relate(s, word)}
                    title={t(`SimilarUniverses.wordHint.${word}`, { name: u.name })}
                  >
                    {t(`SimilarUniverses.word.${word}`)}
                  </AsyncButton>
                ))}
              </ButtonGroup>
            </li>
          );
        })}
      </ul>
      {/* As many as asked for: there may be more — read before they are asked for, the button busy with its own reading alone */}
      {!similar.isPlaceholderData && similar.data?.length === limit && (
        <AsyncButton
          variant="outline"
          size="sm"
          disabled={calm.locked}
          onClick={async () => {
            await queryClient.fetchQuery(listOf(limit + SIMILAR));
            setLimit(limit + SIMILAR);
          }}
        >
          {t("SimilarUniverses.more", { n: SIMILAR })}
        </AsyncButton>
      )}
    </section>
  );
}
