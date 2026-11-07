import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, type SimilarTag, type SimilarTagPage, type TagInfo, type TagRelation, type TagVariantInfo, unwrap } from "@/api/client";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Input } from "@/components/ui/input";
import { sourceTitle } from "@/i18n/label";
import { usePref } from "@/lib/usePref";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { IgnoreButton, type IgnoredFilter, IgnoredSelect, ignoredParam } from "./IgnoreTag";
import { RELATIONS, type Said, THROUGH, type Through } from "./relations";
import { QUERY_SETTLE_MS, TAGS } from "./tagQueries";
import { SiteNames, TagNames } from "./TagNames";

/** How many alike tags are offered for a tag. */
const SIMILAR = 30;

/** The filters of the table: of every relation the user says, and one of the tags related through others. */
type Shown = Record<Said | "THROUGH", boolean>;

/** The quiet after the last answer before the table is read anew. */
const REFRESH_AFTER_MS = 2000;

/** How long the rows move to their places once the table is read anew, and how long the buttons stay locked after that. */
const MOVE_MS = 1000;
const SETTLE_MS = 1000;

/** The key of a row: a normalized tag's, or a site's tag's bound to this one. */
function rowKey(tag: string, site?: TagVariantInfo): string {
  return site ? `site:${site.source}/${site.tag}` : tag;
}

/**
 * The tags alike this one by the meaning of their names, of one site or another — offered only —
 * every tag the user said something of to this one, and every site's tag bound to it. Every row of a
 * tag has the user's four words on it: the same tag (merged), a tag under it, a tag above it, a
 * different one; pressed again, taken back. A row of a site's tag is merged: unpressed, the site's
 * tag is taken out to a tag of its own. A word said keeps the row in its place among the others.
 *
 * The server sorts and filters: the rows of the relations unticked above are left out (the choice
 * kept in the browser), alike the text searched for instead of the tag when one is typed, a page at
 * a time; a page read after the table was sorted anew may repeat a row, shown once.
 *
 * AN ANSWER IS SAVED AT ONCE, THE TABLE READ ANEW AFTER A QUIET: a press is sent at once — the
 * answers one after another, in the order pressed — and shown pressed at once; the table, whose
 * order a merge changes, is read anew two seconds after the last press, in the background, the
 * buttons free. Once it is here the buttons are locked, the rows move to their new places for a
 * second, and the buttons are free a second after — so a row that moved is never pressed by the
 * click meant for the one that was there.
 */
export function Similar({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const [stored, setShown] = usePref<Partial<Shown>>("tags.similar.shown", {});
  const shown: Shown = { MERGED: true, CHILD: true, PARENT: true, DIFFERENT: true, THROUGH: true, ...stored };
  const hidden: TagRelation[] = [...RELATIONS.filter((r) => !shown[r]), ...(shown.THROUGH ? [] : THROUGH)];
  const [ignoredFilter, setIgnoredFilter] = useState<IgnoredFilter>("any");
  const ignored = ignoredParam(ignoredFilter);
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), QUERY_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [input]);
  const [locked, setLocked] = useState(false);
  /** The answers given since the table was read, by tag: shown pressed before the table says so. */
  const [said, setSaid] = useState<Record<string, TagRelation>>({});
  const [error, setError] = useState<string | null>(null);
  const rows = useRef(new Map<string, HTMLLIElement>());
  /** Where every row was before the table was read anew, by tag: what the rows move from. */
  const before = useRef<Map<string, number> | null>(null);
  /** The answers sent, one after another. */
  const sending = useRef<Promise<void>>(Promise.resolve());
  /** How many presses there were: a reading planned before the last one is not the last. */
  const presses = useRef(0);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const unlock = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      clearTimeout(unlock.current);
      clearTimeout(refreshTimer.current);
    },
    [],
  );
  const queryClient = useQueryClient();
  /** A page of the table as the filters and the search are now, read from [offset]. */
  const readPage = async (offset: number, limit: number, asked: { query: string; hidden: TagRelation[]; ignored?: boolean }) =>
    unwrap(
      await api.GET("/api/tags/similar", {
        params: {
          query: { tag: tag.key, query: asked.query || undefined, hidden: asked.hidden, ignored: asked.ignored, offset, limit },
        },
      }),
    );
  const key = [TAGS, "similar", tag.key, query, hidden.join(","), ignoredFilter];
  const similar = useInfiniteQuery({
    queryKey: key,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => readPage(pageParam, SIMILAR, { query, hidden, ignored }),
    // The next page from the rows read so far: a table sorted anew meanwhile repeats a row or two, left out below
    getNextPageParam: (last, pages) => {
      const read = pages.reduce((n, p) => n + p.tags.length, 0);
      return read < last.total ? read : undefined;
    },
  });
  /** The rows of every page read, each once: the first place it was read in stays. */
  const loaded = similar.data?.pages.flatMap((p) => p.tags);
  const seen = new Set<string>();
  const unique = loaded?.filter((s) => {
    const k = rowKey(s.tag.key, s.site ?? undefined);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  /** What the table is read with now: a reading planned before is made with what is current when it is made. */
  const current = useRef({ key, query, hidden, ignored, rows: 0 });
  current.current = { key, query, hidden, ignored, rows: loaded?.length ?? 0 };

  /**
   * The table read anew once every answer is saved — unless the user pressed again meanwhile — in the
   * background, the buttons free; as many rows as were read, in one page; only once it is here are
   * the buttons locked and the rows moved to it.
   */
  const refresh = async (asked: number) => {
    await sending.current;
    if (asked !== presses.current) return;
    const { key, query, hidden, ignored, rows: count } = current.current;
    let fresh: SimilarTagPage;
    try {
      fresh = await readPage(0, Math.max(SIMILAR, count), { query, hidden, ignored });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    // Pressed again while it was read, or the filters changed: the reading after that shows it
    if (asked !== presses.current || current.current.key.join("\u0001") !== key.join("\u0001")) return;
    const signature = (rows: SimilarTag[] | undefined) => rows?.map((s) => `${rowKey(s.tag.key, s.site ?? undefined)}=${s.relation}`).join("|");
    const shownBefore = signature(queryClient.getQueryData<{ pages: SimilarTagPage[] }>(key)?.pages.flatMap((p) => p.tags));
    before.current = new Map([...rows.current].map(([k, el]) => [k, el.getBoundingClientRect().top]));
    queryClient.setQueryData(key, { pages: [fresh], pageParams: [0] });
    if (signature(fresh.tags) === shownBefore) {
      // The same table: nothing moves, nothing is locked
      before.current = null;
      setSaid({});
    } else {
      setLocked(true);
    }
    // The tag itself, the list and the works read anew too; the table is current already — read again, it would move without the rows
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [TAGS], predicate: (q) => q.queryKey[1] !== "similar" }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      queryClient.invalidateQueries({ queryKey: ["facets"] }),
    ]);
  };

  /** The user's word on a row: of a tag, what it is to this one; of a site's tag, bound to this one or taken out to one of its own. */
  const answer = (other: string, relation: TagRelation, site?: TagVariantInfo) => {
    const row = rowKey(other, site);
    setSaid((s) => ({ ...s, [row]: relation }));
    setError(null);
    const asked = ++presses.current;
      sending.current = sending.current.then(async () => {
      try {
        if (site)
          unwrap(
            await api.PUT("/api/tags/binding", {
              body: { source: site.source, siteTag: site.tag, tag: relation === "MERGED" ? tag.key : undefined },
            }),
          );
        else unwrap(await api.PUT("/api/tags/relation", { body: { tag: tag.key, other, relation } }));
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        // Not saved: the row shows what the table says again
        setSaid((s) => Object.fromEntries(Object.entries(s).filter(([k]) => k !== row)));
      }
    });
    clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(() => void refresh(asked), REFRESH_AFTER_MS);
  };

  // The rows read anew: each moved from where it was to where it is, a new one faded in
  useLayoutEffect(() => {
    const was = before.current;
    if (!was || !similar.data) return;
    before.current = null;
    setSaid({});
    rows.current.forEach((el, key) => {
      const top = was.get(key);
      el.style.transition = "none";
      if (top === undefined) el.style.opacity = "0";
      else el.style.transform = `translateY(${top - el.getBoundingClientRect().top}px)`;
    });
    requestAnimationFrame(() =>
      rows.current.forEach((el) => {
        el.style.transition = `transform ${MOVE_MS}ms ease, opacity ${MOVE_MS}ms ease`;
        el.style.transform = "";
        el.style.opacity = "";
      }),
    );
    clearTimeout(unlock.current);
    unlock.current = setTimeout(() => setLocked(false), MOVE_MS + SETTLE_MS);
  }, [similar.data]);

  const visible = unique;
  const total = similar.data?.pages.at(-1)?.total;

  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Similar.title")}</h2>
      <p className="text-xs text-muted-foreground">{t("Similar.help")}</p>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={t("Similar.searchPlaceholder")}
          className="pl-8"
          type="search"
        />
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <span className="text-muted-foreground">{t("Similar.show")}</span>
        {RELATIONS.map((r) => (
          <label key={r} className="flex items-center gap-1">
            <input type="checkbox" checked={shown[r]} onChange={() => setShown({ ...shown, [r]: !shown[r] })} className="size-3.5" />
            {t(`Relation.${r}.filter`)}
          </label>
        ))}
        <label className="flex items-center gap-1" title={t("Similar.throughTitle")}>
          <input type="checkbox" checked={shown.THROUGH} onChange={() => setShown({ ...shown, THROUGH: !shown.THROUGH })} className="size-3.5" />
          {t("Similar.through")}
        </label>
        <IgnoredSelect value={ignoredFilter} onChange={setIgnoredFilter} />
        {(locked || similar.isFetching) && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
      </div>
      {similar.isLoading && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" /> {t("Similar.searching")}
        </p>
      )}
      {similar.isError && <p className="text-destructive">{t("Similar.loadError", { error: similar.error.message })}</p>}
      {visible && visible.length === 0 && <p className="text-muted-foreground">{t("Similar.none")}</p>}
      {error && <p className="text-destructive">{t("Similar.saveError", { error })}</p>}
      {visible && visible.length > 0 && (
        <ul className={cn("flex flex-col divide-y rounded-lg border", locked && "pointer-events-none")} aria-busy={locked}>
          {visible.map(({ tag: s, similarity, relation: read, site: given }) => {
            // A row of a tag comes with no site's tag: null, not left out
            const site = given ?? undefined;
            const row = rowKey(s.key, site);
            // Pressed as the user said it, before the table is read anew
            const relation = said[row] ?? read;
            // The only site's tag of this one is not taken out: the tag would be left with none
            const only = site !== undefined && tag.variants.length === 1;
            return (
            <li
              key={row}
              ref={(el) => {
                if (el) rows.current.set(row, el);
                else rows.current.delete(row);
              }}
              className={cn("flex flex-wrap items-center gap-x-3 gap-y-1.5 bg-background px-3 py-2", relation !== "NONE" && "bg-muted/40")}
            >
              {/* The name in full and the sites under it; on a narrow screen the buttons take a line of their own */}
              <div className="flex min-w-0 flex-1 basis-48 flex-col">
                {site ? (
                  <span className="break-words" title={t("Similar.siteTagTitle")}>
                    {site.name}
                  </span>
                ) : (
                  <TagNames tag={s}>
                    <a
                      href={screenHref({ kind: "tags", tag: s.key })}
                      onClick={(e) => followLink(e, { kind: "tags", tag: s.key })}
                      className="max-w-full shrink-0 break-words underline-offset-2 hover:underline"
                      title={s.variants.map((v) => `${sourceTitle(v.source)}: ${v.name}`).join(" · ")}
                    >
                      {s.name}
                    </a>
                  </TagNames>
                )}
                {site ? (
                  <span className="truncate text-xs text-muted-foreground">{t("Similar.siteTagOf", { site: sourceTitle(site.source) })}</span>
                ) : (
                  <SiteNames tag={s} />
                )}
              </div>
              {!site && <IgnoreButton tag={s} disabled={locked} />}
              <span className="shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                <span title={t("Similar.works")}>{site ? site.works : s.works}</span>
                {" · "}
                <span title={t("Similar.similarity")}>{Math.round(similarity * 100)}%</span>
              </span>
              {(THROUGH as TagRelation[]).includes(relation) ? (
                // Related through other tags: said of the tags between, not here
                <span className="text-xs text-muted-foreground" title={t("Similar.throughTitle")}>
                  {t(`Similar.${relation as Through}`)}
                </span>
              ) : (
              <ButtonGroup className="w-full sm:w-fit">
                {RELATIONS.map((r) => {
                  const pressed = relation === r;
                  // A site's tag is bound to a tag or not: under, above, different are said of the tag it is taken out to
                  const ofTags = site !== undefined && r !== "MERGED";
                  const title = t(`Relation.${r}.title`);
                  return (
                    <Button
                      key={r}
                      variant={pressed ? "default" : "outline"}
                      size="xs"
                      className="flex-1 sm:flex-none"
                      aria-pressed={pressed}
                      disabled={locked || ofTags || (only && pressed)}
                      onClick={() => answer(s.key, pressed ? "NONE" : r, site)}
                      title={
                        ofTags
                          ? t("Similar.siteTagFirst")
                          : only && pressed
                            ? t("Similar.onlySiteTag")
                            : pressed
                              ? t("Similar.pressAgain", { title })
                              : title
                      }
                    >
                      {t(`Relation.${r}.label`)}
                    </Button>
                  );
                })}
              </ButtonGroup>
              )}
            </li>
            );
          })}
        </ul>
      )}
      {similar.hasNextPage && (
        <Button variant="outline" size="sm" onClick={() => void similar.fetchNextPage()} disabled={similar.isFetchingNextPage || locked}>
          {similar.isFetchingNextPage && <Loader2Icon className="animate-spin" />} {t("Similar.more")}
          {total !== undefined && visible && <span className="text-muted-foreground">{t("Similar.shownOf", { shown: visible.length, total })}</span>}
        </Button>
      )}
    </section>
  );
}
