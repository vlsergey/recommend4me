import { useEffect, useRef, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, RefreshCwIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type HierarchyOffer, type HierarchyOffers, type TagRef, unwrap } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { TAGS, useTagsChanged } from "./tagQueries";

/** How many offers of the tags' graph are shown, and added by "more". */
const OFFERS_PAGE = 20;

/** The quiet after the user's last mark on the offers before they are read anew: as an answer's on a work. */
const QUIET_MS = 2000;

/** How often the offers being worked out anew are asked for. */
const PENDING_POLL_MS = 2000;

/** An offer as one: its kind, its tags, its entry. */
const offerKey = (o: HierarchyOffer) => `${o.kind}/${o.tags.map((r) => r.key).join("/")}/${o.entry ?? ""}`;

/** The entry of the catalogue an offer of a tag above others is of: every such offer names one. */
function entryOf(o: HierarchyOffer): { catalogue: string; entry: string } {
  if (o.catalogue === undefined || o.entry === undefined) throw new Error(`The offer ${offerKey(o)} names no entry of a catalogue`);
  return { catalogue: o.catalogue, entry: o.entry };
}

/** A tag's name, a link to its page. */
function TagLink({ tag }: { tag: TagRef }) {
  return (
    <a href={screenHref({ kind: "tags", tag: tag.key })} onClick={(e) => followLink(e, { kind: "tags", tag: tag.key })} className="font-medium underline-offset-2 hover:underline">
      {tag.name}
    </a>
  );
}

/**
 * THE TOP OFFERS OF THE TAGS' GRAPH by the catalogues, the ones that change most of the graded works
 * first: a tag under another, two tags of one entry merged, a new tag above two or more. Read apart
 * from the list, the old shown while read anew; each said by its own button, or left as it is.
 */
export function HierarchyOfferList() {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  const [limit, setLimit] = useState(OFFERS_PAGE);
  // The offers the user said are too general: hidden unless asked for
  const [showRejected, setShowRejected] = useState(false);
  const queryClient = useQueryClient();
  const offersKey = [TAGS, "hierarchy-offers"];
  const offers = useQuery({
    queryKey: offersKey,
    placeholderData: keepPreviousData,
    queryFn: async ({ signal }) => unwrap(await api.GET("/api/tags/hierarchy-offers", { signal })),
    // Worked out anew in the queue of the computations: asked again till they are — one answer awaited, not an area reading itself again
    refetchInterval: (query) => (query.state.data?.pending ? PENDING_POLL_MS : false),
  });
  // The offers said here while they are worked out anew: not shown again till they are
  const [acted, setActed] = useState<ReadonlySet<string>>(new Set());
  const pending = offers.data?.pending ?? false;
  useEffect(() => {
    if (offers.data && !pending) setActed(new Set());
  }, [offers.data, pending]);
  // The offers read anew once the user's marks have gone quiet for a while: one reading for a row of them
  const quiet = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(quiet.current), []);
  const readLater = () => {
    clearTimeout(quiet.current);
    quiet.current = setTimeout(() => void queryClient.invalidateQueries({ queryKey: offersKey }), QUIET_MS);
  };
  const act = async (o: HierarchyOffer) => {
    const [first, second] = o.tags;
    if (o.kind === "LINK") unwrap(await api.PUT("/api/tags/relation", { body: { tag: first.key, other: second.key, relation: "PARENT" } }));
    else if (o.kind === "MERGE") unwrap(await api.PUT("/api/tags/relation", { body: { tag: first.key, other: second.key, relation: "MERGED" } }));
    else if (o.kind === "UNLINK") unwrap(await api.PUT("/api/tags/relation", { body: { tag: first.key, other: second.key, relation: "NONE" } }));
    else
      unwrap(
        await api.POST("/api/tags/above", {
          body: { ...entryOf(o), children: o.tags.map((r) => r.key), tag: o.existing?.key },
        }),
      );
    setActed((s) => new Set(s).add(offerKey(o)));
    await changed();
  };
  /**
   * The user's word that the entry is too general to be a tag above others — or its taking back:
   * the button waits for its writing alone, the offers of the entry shown so at once; the list read
   * anew after the quiet ([readLater]), not waited for — several of them said one after another.
   */
  const tooGeneral = async (o: HierarchyOffer, said: boolean) => {
    ensureOk(await api.PUT("/api/tags/too-general", { body: { ...entryOf(o), tooGeneral: said } }));
    queryClient.setQueryData<HierarchyOffers>(offersKey, (kept) =>
      kept && { ...kept, offers: kept.offers.map((x) => (x.catalogue === o.catalogue && x.entry === o.entry ? { ...x, rejected: said } : x)) },
    );
    readLater();
  };
  const shown = (offers.data?.offers ?? []).filter((o) => !acted.has(offerKey(o)));
  const rejected = shown.filter((o) => o.rejected).length;
  const list = shown.filter((o) => showRejected || !o.rejected);
  return (
    <section className="flex flex-col gap-2 text-sm">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">{t("Hierarchy.title")}</h2>
        {offers.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        <AsyncButton variant="ghost" size="xs" className="ml-auto" onClick={() => offers.refetch()} title={t("Hierarchy.refreshTitle")} icon={<RefreshCwIcon />}>
          {t("Hierarchy.refresh")}
        </AsyncButton>
      </div>
      <p className="text-xs text-muted-foreground">{t("Hierarchy.hint")}</p>
      {pending && <p className="text-xs text-muted-foreground">{t("Hierarchy.pending")}</p>}
      {rejected > 0 && (
        <label className="flex items-center gap-1 text-xs" title={t("Hierarchy.showRejectedTitle")}>
          <input type="checkbox" checked={showRejected} onChange={() => setShowRejected(!showRejected)} className="size-3.5" />
          {t("Hierarchy.showRejected", { count: rejected })}
        </label>
      )}
      {offers.isError && <p className="text-destructive">{t("Hierarchy.loadError", { error: offers.error.message })}</p>}
      {offers.data && list.length === 0 && <p className="text-muted-foreground">{t("Hierarchy.none")}</p>}
      {list.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {list.slice(0, limit).map((o) => (
            <li
              key={offerKey(o)}
              className={cn("flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5", o.rejected && "text-muted-foreground")}
            >
              <div className="flex min-w-0 flex-1 flex-col">
                <span>
                  {o.kind === "LINK" && (
                    <>
                      <TagLink tag={o.tags[0]} /> {t("Hierarchy.under")} <TagLink tag={o.tags[1]} />
                    </>
                  )}
                  {o.kind === "MERGE" && (
                    <>
                      <TagLink tag={o.tags[1]} /> {t("Hierarchy.into")} <TagLink tag={o.tags[0]} />
                    </>
                  )}
                  {o.kind === "BIND_PARENT" && o.existing && (
                    <>
                      <TagLink tag={o.existing} /> {t("Hierarchy.as")}{" "}
                      <a href={o.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
                        «{o.path[0]}» ({o.entry})
                      </a>{" "}
                      {t("Hierarchy.above")}{" "}
                      {o.tags.map((r, i) => (
                        <span key={r.key}>
                          {i > 0 && ", "}
                          <TagLink tag={r} />
                        </span>
                      ))}
                    </>
                  )}
                  {o.kind === "UNLINK" && (
                    <>
                      <TagLink tag={o.tags[0]} /> {t("Hierarchy.under")} <TagLink tag={o.tags[1]} /> — {t("Hierarchy.through")} <TagLink tag={o.tags[2]} />
                    </>
                  )}
                  {o.kind === "NEW_PARENT" && (
                    <>
                      {t("Hierarchy.newTag")}{" "}
                      <a href={o.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                        «{o.path[0]}»
                      </a>{" "}
                      {t("Hierarchy.above")}{" "}
                      {o.tags.map((r, i) => (
                        <span key={r.key}>
                          {i > 0 && ", "}
                          <TagLink tag={r} />
                        </span>
                      ))}
                    </>
                  )}
                </span>
                {o.kind === "UNLINK" ? (
                  <span className="text-xs text-muted-foreground">
                    {o.tags[0].name} → {o.tags[2].name} → {o.tags[1].name} · {t("Hierarchy.noWorks")}
                  </span>
                ) : (
                  <span className="text-xs text-muted-foreground">
                    {o.catalogue !== undefined && <>{sourceTitle(o.catalogue)}: </>}
                    {o.kind === "MERGE" ? t("Hierarchy.sameEntry", { entry: `${o.path[0]} (${o.entry})` }) : o.path.join(" → ")} ·{" "}
                    {o.kind !== "MERGE" && <>{t("Hierarchy.levels", { count: o.levels })} · </>}
                    {t("Hierarchy.graded", { count: o.graded })}
                    {o.rejected && <> · {t("Hierarchy.rejected")}</>}
                  </span>
                )}
              </div>
              {o.rejected ? (
                <AsyncButton variant="outline" size="sm" onClick={() => tooGeneral(o, false)} title={t("Hierarchy.takeBack.title")}>
                  {t("Hierarchy.takeBack.label")}
                </AsyncButton>
              ) : (
                <>
                  {(o.kind === "NEW_PARENT" || o.kind === "BIND_PARENT") && (
                    <AsyncButton variant="ghost" size="sm" onClick={() => tooGeneral(o, true)} title={t("Hierarchy.tooGeneral.title")}>
                      {t("Hierarchy.tooGeneral.label")}
                    </AsyncButton>
                  )}
                  <AsyncButton variant="outline" size="sm" onClick={() => act(o)} title={t(`Hierarchy.${o.kind}.title`)}>
                    {t(`Hierarchy.${o.kind}.label`)}
                  </AsyncButton>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {list.length > limit && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + OFFERS_PAGE)}>
          {t("Hierarchy.more", { count: list.length - limit })}
        </Button>
      )}
    </section>
  );
}
