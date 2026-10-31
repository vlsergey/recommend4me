import { useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, ExternalLinkIcon, StarIcon, XIcon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import {
  api,
  ensureOk,
  sameItem,
  unwrap,
  type ContentTypeInfo,
  type FeatureContribution,
  type Grade,
  type ItemDetails,
  type ItemFacet,
  type ItemRef,
  type ItemSummary,
  type SourceInfo,
} from "@/api/client";
import { Button } from "@/components/ui/button";
import { DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { gradeLabel, useSource } from "@/contenttype/types";
import { SiteFacet } from "@/correction/FacetCorrections";
import { CorrectedMark } from "@/correction/FieldEditor";
import { LinkSearch } from "@/correction/Links";
import { useCorrections, type Corrections } from "@/correction/useCorrections";
import { chipKey, ValueActions, type AskedValue } from "@/correction/valueActions";
import { WorkDuplicates, WorkRelations } from "@/duplicate/WorkDuplicates";
import { decided } from "@/facet/valueOrder";
import { score } from "@/i18n/format";
import { label, sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { Explanation } from "@/model/Explanation";
import { Parts } from "@/part/Parts";
import { PictureMatches, Pictures } from "@/picture/Pictures";
import { ReviewMatches, Reviews } from "@/review/Reviews";
import { SearchJudge } from "@/search/SearchJudge";
import { TagsByText } from "@/tagtext/TagsByText";
import { Evidence } from "@/universe/MentionsDialog";
import { itemHref, readItemHash, writeItemHash } from "./itemHash";
import { ItemNumbers } from "./ItemNumbers";
import { ItemTexts } from "./ItemTexts";
import { ITEMS, itemKey } from "./lists";
import { Cover, InterestButtons, RatingButtons, ScoreBadge, SearchMatchLine, SignalBadges } from "./pieces";
import { RelatedWorks } from "./RelatedWorks";
import { RemoveItem } from "./RemoveItem";
import { FITTING, useHeldOut } from "./useHeldOut";
import { Byline, FactLine } from "./WorkFacts";
import { WorkForm } from "./WorkForm";
import { NotFetched, Part, Ratings, RefreshButton } from "./WorkParts";
import { SitesTable } from "./WorkSites";

/** The dialog as the whole screen: there is no outside to press. */
export const WORK_SCREEN_CLASS = "flex h-dvh w-screen max-w-none flex-col gap-0 overflow-hidden rounded-none p-0 ring-0 sm:w-screen";

/** The facets whose values the texts are searched for. */
const MENTIONED: readonly string[] = ["universe", "characters", "secondary-characters", "pairings"];

/** What the left page shows, one at a time. */
type View = "work" | "chapters" | "reviews" | "pictures" | "why" | "sites" | "evidence";

/**
 * THE SCREEN OF A WORK (docs/work-screen.md): the form of the work on the right, every field under
 * what every site says of it; on the left what there is to read, of the site chosen there, which
 * changes nothing of the form; the verdict in the head. On a narrow screen the pages are one at a
 * time, and the verdict folds: a missed tap under the last ✕ neither grades nor overwrites.
 */
export function WorkScreen({
  type,
  source: listedSource,
  summary,
  index,
  count,
  total,
  onIndex,
  onRate,
  onJudge,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  summary: ItemSummary;
  index: number;
  count: number;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (grade: Grade | null) => unknown;
  /** Found by a search: the user's word on whether it answers the line. */
  onJudge?: (relevant: boolean | null) => Promise<unknown>;
}) {
  const { t } = useTranslation("item");
  const queryClient = useQueryClient();
  // The item the form reads the work through, and the one read on the left: another only when chosen
  const [ref, setRef] = useState<ItemRef>({ source: summary.source, item: summary.item });
  const [read, setRead] = useState<ItemRef>(ref);
  const queryOf = (item: ItemRef) => ({
    queryKey: itemKey(item),
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: item } })),
  });
  const details = useQuery(queryOf(ref));
  const other = useQuery({ ...queryOf(read), enabled: !sameItem(read, ref), placeholderData: keepPreviousData });
  const corrections = useCorrections(ref, type.id);
  // A phone opens on the work while it is to be read, on the answers once graded
  const [page, setPage] = useState<"answers" | "work">(summary.grade ? "answers" : "work");
  const [view, setView] = useState<View>("work");
  const [verdictOpen, setVerdictOpen] = useState(false);
  const [asked, setAsked] = useState<AskedValue | null>(null);
  const [revealed, setRevealed] = useState<string>();
  const d = details.data;
  const reading = sameItem(read, ref) ? d : other.data;
  const shown = d?.summary ?? summary;
  const source = useSource(shown.source)?.source ?? listedSource;
  const leftSource = useSource(read.source)?.source ?? source;
  const facets = d?.allFacets ?? summary.facets;
  const tagFacet = d?.allFacets.find((f) => f.tags && f.editable)?.facet;
  const toAnswer = (d?.allFacets.filter((f) => f.editable).flatMap((f) => f.values) ?? []).filter((v) => !decided(v) && !v.offered && !v.implied).length;
  // The grade as the work has it, the list's being gone once it leaves the list; while it is read, as the press gave it
  const graded = details.isFetching ? summary : shown;
  const grade = graded.grade as Grade | undefined;
  const earlier = !grade && graded.previousGrade ? { grade: graded.previousGrade as Grade, version: graded.previousGradeVersion } : undefined;
  const moveTo = (site: ItemRef) => {
    setRef(site);
    setRead(site);
  };

  // The chip a feature of the explanation led to, brought into sight
  useEffect(() => {
    if (revealed) document.querySelector(`[data-value="${CSS.escape(revealed)}"]`)?.scrollIntoView({ block: "center" });
  }, [revealed]);

  const actions = useMemo(
    () => ({
      item: ref,
      sites: d?.sites,
      revealed,
      evidence: (value: AskedValue) => {
        setAsked(value);
        setView("evidence");
        setPage("work");
      },
      evidenceBody: (value: AskedValue) => <EvidenceBody type={type} item={ref} asked={value} facets={d?.allFacets ?? []} corrections={corrections} />,
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- [corrections] is made anew at every drawing and acts by [ref]
    [ref, revealed, type, d?.allFacets, d?.sites],
  );

  /** The value of a feature among the answers, if it is there to be led to. */
  const leadTo = (feature: FeatureContribution) => {
    const found = /^facet:([^:]+):(.+)$/.exec(feature.feature);
    if (!found) return undefined;
    const facet = source.facets.find((f) => f.id === found[1])?.key;
    const key = found[2];
    if (!facet || !d?.allFacets.find((f) => f.facet === facet)?.values.some((v) => v.key === key)) return undefined;
    return () => {
      setRevealed(chipKey(facet, key));
      setPage("answers");
    };
  };

  /** An item taken out of the work: the screen stays on one that is left, the address with it. */
  const takeOut = async (site: ItemRef) => {
    const rest = d?.sites.find((s) => !sameItem(s, site));
    if (!rest) return;
    const left = { source: rest.source, item: rest.item };
    if (sameItem(site, ref)) {
      ensureOk(await api.DELETE("/api/items/{source}/{item}/links", { params: { path: left, query: { otherSource: site.source, otherItem: site.item } } }));
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      queryClient.invalidateQueries({ queryKey: itemKey(left) });
      setRef(left);
    } else await corrections.unlink(site);
    if (sameItem(site, read)) setRead(left);
    // The list's own item taken out: this screen is of it no more, the work left opens by the address
    if (sameItem(site, summary)) {
      onIndex(null);
      setTimeout(() => {
        window.location.hash = itemHref(left);
      });
    } else {
      const named = readItemHash();
      if (named && sameItem(named, site)) writeItemHash(left);
    }
  };

  return (
    <ValueActions.Provider value={actions}>
      <header className="shrink-0 border-b px-3 py-2 pr-11">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <div className="flex items-center">
            <Button variant="ghost" size="icon-sm" onClick={() => onIndex(Math.max(index - 1, 0))} disabled={index === 0} title={t("ItemDialog.previous")}>
              <ChevronLeftIcon />
            </Button>
            <span className="text-xs tabular-nums text-muted-foreground">
              {index + 1}/{total}
            </span>
            <Button variant="ghost" size="icon-sm" onClick={() => onIndex(Math.min(index + 1, count - 1))} disabled={index >= count - 1} title={t("ItemDialog.next")}>
              <ChevronRightIcon />
            </Button>
          </div>
          {/* A short screen: the head folds to its two lines, and what it holds besides stands on the left page */}
          <div className={cn("h-10 shrink-0 overflow-hidden rounded bg-muted [@media(max-height:40rem)]:hidden", source.picturesTell ? "w-20" : "w-14")}>
            <Cover typeId={type.id} summary={shown} className="size-full" />
          </div>
          <div className="min-w-0 flex-1 basis-64">
            <div className="flex items-center gap-1">
              <DialogTitle className="text-lg leading-tight font-semibold break-words">{shown.title}</DialogTitle>
              {d?.titleCorrected && <CorrectedMark />}
            </div>
            <DialogDescription render={<div />} className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground [@media(max-height:40rem)]:sr-only">
              <Byline source={source} facets={facets} />
              <FactLine source={source} summary={shown} facets={facets} />
            </DialogDescription>
          </div>
          {tagFacet && <TagsByText item={ref} facet={tagFacet} corrections={corrections} size="sm" className="" />}
        </div>
        {/* A phone keeps the verdict folded: the buttons under a thumb are the answers' */}
        <Button variant="outline" size="sm" className="mt-1 w-full justify-between min-[56rem]:hidden" aria-expanded={verdictOpen} onClick={() => setVerdictOpen(!verdictOpen)}>
          <span className="min-w-0 truncate">
            {t("WorkScreen.verdict")}
            {grade && ` · ${grade} ${gradeLabel(type.id, grade)}`}
          </span>
          <ChevronDownIcon className={cn("transition-transform", verdictOpen && "rotate-180")} />
        </Button>
        <div className={cn("mt-1 flex-wrap items-center gap-x-3 gap-y-1 [&_button]:pointer-coarse:h-11", verdictOpen ? "flex" : "hidden min-[56rem]:flex")}>
          {/* Keyed by the work: a press at work on one is not shown on the next the screen moves to */}
          <InterestButtons key={`interest:${summary.source}/${summary.item}`} summary={{ ...summary, grade: graded.grade, previousGrade: graded.previousGrade }} labels />
          <RatingButtons
            key={`grade:${summary.source}/${summary.item}`}
            type={type}
            value={grade}
            onRate={onRate}
            size="sm"
            labels
            earlier={earlier}
            className="min-w-0 flex-1 basis-full min-[56rem]:basis-[36rem]"
          />
          {grade && <span className="w-full text-xs text-muted-foreground sm:hidden">{gradeLabel(type.id, grade)}</span>}
          <PredictionNumber type={type} summary={graded} />
          {earlier && <span className="text-xs text-muted-foreground">{t("RatingButtons.newVersion")}</span>}
        </div>
      </header>

      <div className="flex shrink-0 gap-1 border-b p-1 min-[56rem]:hidden">
        <Button variant={page === "answers" ? "secondary" : "ghost"} size="sm" className="flex-1" onClick={() => setPage("answers")}>
          {t("WorkScreen.answers", { count: toAnswer })}
        </Button>
        <Button variant={page === "work" ? "secondary" : "ghost"} size="sm" className="flex-1" onClick={() => setPage("work")}>
          {t("WorkScreen.work")}
        </Button>
      </div>

      <div className="grid min-h-0 flex-1 min-[56rem]:grid-cols-2 min-[56rem]:divide-x">
        <section className={cn("min-h-0 min-w-0 overflow-y-auto", page === "work" ? "block" : "hidden min-[56rem]:block")}>
          {!reading && (details.isLoading || other.isLoading) ? (
            <Loading />
          ) : reading ? (
            <LeftPage
              type={type}
              source={leftSource}
              details={reading}
              sites={d?.sites ?? reading.sites}
              listed={summary}
              corrections={corrections}
              view={view}
              onView={setView}
              onShow={setRead}
              onMove={moveTo}
              onTakeOut={takeOut}
              shown={read}
              asked={asked}
              onLeave={() => {
                setAsked(null);
                setView("work");
              }}
              leadTo={leadTo}
              onJudge={onJudge}
            />
          ) : (
            <div className="p-4 text-destructive">{t("ItemDialog.detailsFailed")}</div>
          )}
        </section>
        <section className={cn("min-h-0 min-w-0 overflow-y-auto p-3", page === "answers" ? "block" : "hidden min-[56rem]:block")}>
          {details.isLoading ? (
            <Loading />
          ) : d ? (
            <Part title={t("ItemDialog.facets")}>
              <WorkForm type={type} source={source} details={d} item={ref} corrections={corrections} />
            </Part>
          ) : null}
        </section>
      </div>
    </ValueActions.Provider>
  );
}

function Loading() {
  return (
    <div className="grid gap-3 p-4">
      <Skeleton className="h-5 w-40" />
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-24 w-full" />
    </div>
  );
}

/** The sites of the work, the one that says the most first: a press chooses what the left page reads, the arrow opens its page. */
function SiteMarks({ sites, shown, onShow }: { sites: { source: string; item: string; url: string; title: string }[]; shown: ItemRef; onShow: (site: ItemRef) => void }) {
  const { t } = useTranslation("item");
  return (
    <div className="flex flex-wrap items-center gap-1">
      <span className="text-xs text-muted-foreground">{t("WorkScreen.reading")}</span>
      {sites.map((s) => (
        <span key={`${s.source}/${s.item}`} className="inline-flex items-center">
          <Button
            variant={sameItem(s, shown) ? "secondary" : "ghost"}
            size="xs"
            aria-pressed={sameItem(s, shown)}
            title={`${sourceTitle(s.source)}: ${s.title}`}
            onClick={() => onShow({ source: s.source, item: s.item })}
          >
            {sourceShort(s.source)}
          </Button>
          <a href={s.url} target="_blank" rel="noreferrer" className="p-1 text-muted-foreground hover:text-foreground" title={sourceTitle(s.source)}>
            <ExternalLinkIcon className="size-3" />
          </a>
        </span>
      ))}
    </div>
  );
}

/**
 * The model's word beside the grades, as a number and never on them: a mark on the very control the
 * model learns from would anchor the grade. Of a graded work the number of the model that had not
 * seen it, drawn as such; till there is one, says so.
 */
function PredictionNumber({ type, summary }: { type: ContentTypeInfo; summary: ItemSummary }) {
  const { t } = useTranslation("item");
  if (summary.grade == null) return <ScoreBadge type={type} summary={summary} />;
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground" title={t("ItemDialog.withoutGrade")}>
      <StarIcon className="size-3" />
      {summary.heldOut ? t("WorkScreen.heldOut", { score: score(Math.max(0, summary.heldOut.score)) }) : t("WorkScreen.noEstimate")}
    </span>
  );
}

/** Where a value comes from: its name, and what the counts were made of; a value of no passages says so and offers the tags by the text. */
function EvidenceBody({ type, item, asked, facets, corrections }: { type: ContentTypeInfo; item: ItemRef; asked: AskedValue; facets: ItemFacet[]; corrections: Corrections }) {
  const { t } = useTranslation("item");
  const universes = facets.find((f) => f.facet === "universe")?.values.filter((v) => v.corrected !== "REMOVED" && !v.offered) ?? [];
  return (
    <div className="flex flex-col gap-3 text-sm">
      {asked.url && (
        <a href={asked.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 self-start text-xs text-muted-foreground hover:text-foreground">
          {asked.name} <ExternalLinkIcon className="size-3" />
        </a>
      )}
      {asked.summary && <p className="text-muted-foreground">{asked.summary}</p>}
      {MENTIONED.includes(asked.facet) ? (
        <Evidence type={type} item={item} facet={asked.facet} value={asked.value} universes={universes} />
      ) : (
        <>
          <p className="text-muted-foreground">{t("WorkScreen.noPassages")}</p>
          {facets.find((f) => f.facet === asked.facet)?.tags && <TagsByText item={item} facet={asked.facet} corrections={corrections} size="sm" className="" />}
        </>
      )}
    </div>
  );
}

/** The left page: one view at a time of what there is to read. It never covers the answers. */
function LeftPage({
  type,
  source,
  details: d,
  sites,
  listed,
  corrections,
  view,
  onView,
  onShow,
  onMove,
  onTakeOut,
  shown,
  asked,
  onLeave,
  leadTo,
  onJudge,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  sites: ItemDetails["sites"];
  /** The work as its list has it: what the search found in it. */
  listed: ItemSummary;
  corrections: Corrections;
  view: View;
  onView: (view: View) => void;
  onShow: (site: ItemRef) => void;
  /** The screen moved to another item: the one it read is removed. */
  onMove: (site: ItemRef) => void;
  onTakeOut: (site: ItemRef) => Promise<unknown>;
  shown: ItemRef;
  asked: AskedValue | null;
  onLeave: () => void;
  leadTo: (feature: FeatureContribution) => (() => void) | undefined;
  onJudge?: (relevant: boolean | null) => Promise<unknown>;
}) {
  const { t } = useTranslation("item");
  const ref = { source: d.summary.source, item: d.summary.item };
  const reviewsLabel = source.reviewsLabelKey ? label(source.reviewsLabelKey) : t("ItemDialog.reviews");
  const partsLabel = source.partsLabelKey ? label(source.partsLabelKey) : t("ItemDialog.parts");
  const nothing = d.texts.length === 0 && d.pictures.length === 0 && d.reviews.length === 0 && d.parts.length === 0;
  const rest = sites.find((s) => !sameItem(s, ref));
  // A facet of the site that is no field's source: the rest stand above their fields
  const originals = new Set(source.facets.filter((f) => f.role === "ORIGINAL").map((f) => f.key));
  const facts = d.allFacets.filter((f) => !f.editable && !originals.has(f.facet));
  return (
    <div className="flex flex-col gap-3 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <SiteMarks sites={sites} shown={shown} onShow={onShow} />
        {d.canRefresh && <RefreshButton item={ref} />}
      </div>
      <Tabs value={view} onValueChange={(v) => onView(v as View)}>
        <TabsList className="h-auto flex-wrap">
          <TabsTrigger value="work">{t("WorkScreen.work")}</TabsTrigger>
          <TabsTrigger value="chapters">
            {partsLabel} {d.parts.length}
          </TabsTrigger>
          <TabsTrigger value="reviews">
            {reviewsLabel} {d.reviewCount ?? d.reviews.length}
          </TabsTrigger>
          <TabsTrigger value="pictures">
            {t("ItemDialog.pictures")} {d.pictures.length}
          </TabsTrigger>
          <TabsTrigger value="why">{t("WorkScreen.why")}</TabsTrigger>
          <TabsTrigger value="sites">
            {t("WorkScreen.sites")} {sites.length}
          </TabsTrigger>
          {asked && <TabsTrigger value="evidence">{t("WorkScreen.evidence", { name: asked.name })}</TabsTrigger>}
        </TabsList>
      </Tabs>
      {view === "work" && (
        <>
          {nothing && d.canRefresh && <NotFetched item={ref} />}
          {listed.searchMatch && (
            <div className="flex flex-col gap-1">
              <SearchMatchLine match={listed.searchMatch} />
              {onJudge && <SearchJudge value={listed.searchRelevant} onJudge={onJudge} />}
            </div>
          )}
          <SignalBadges source={source} signals={d.summary.signals} />
          {/* What the head folds away on a short screen */}
          <FactLine source={source} summary={d.summary} facets={d.allFacets} className="hidden text-xs text-muted-foreground [@media(max-height:40rem)]:flex" />
          {source.picturesTell && (
            <Part title={t("ItemDialog.pictures")}>
              <Pictures item={ref} pictures={d.pictures} explained />
            </Part>
          )}
          <Part title={t("ItemDialog.texts")}>
            <ItemTexts texts={d.texts} source={source} corrections={corrections} />
          </Part>
          {facts.length > 0 && (
            <Part title={t("WorkScreen.facts")}>
              <section className="flex flex-col gap-2">
                {facts.map((f) => (
                  <SiteFacet key={f.facet} facet={f} />
                ))}
              </section>
            </Part>
          )}
          <Part title={t("ItemDialog.numbers")}>
            <ItemNumbers numbers={d.allNumbers} source={source} />
          </Part>
          <Part title={t("ItemDialog.seriesAndAuthor")}>
            <RelatedWorks type={type} related={d.related} />
          </Part>
        </>
      )}
      {view === "chapters" && (
        <Part title={partsLabel}>
          <Parts item={ref} label={partsLabel} parts={d.parts} unfolded />
        </Part>
      )}
      {view === "reviews" && (
        <>
          <Part title={reviewsLabel}>
            <Reviews item={ref} label={reviewsLabel} reviews={d.reviews} total={d.reviewCount} explained />
          </Part>
          <Part title={t("ItemDialog.similarReviews")}>
            <ReviewMatches item={ref} matches={d.reviewMatches} />
          </Part>
        </>
      )}
      {view === "pictures" && (
        <>
          <Part title={t("ItemDialog.pictures")}>
            <Pictures item={ref} pictures={d.pictures} large={source.picturesTell} explained />
          </Part>
          <Part title={t("ItemDialog.similarPictures")}>
            <PictureMatches item={ref} matches={d.pictureMatches} />
          </Part>
        </>
      )}
      {view === "why" && <WhyView type={type} details={d} leadTo={leadTo} />}
      {view === "sites" && (
        <>
          <Part title={t("WorkScreen.sites")}>
            <SitesTable type={type} sites={sites} shown={shown} onShow={onShow} onTakeOut={onTakeOut} />
          </Part>
          <Part title={t("ItemDialog.sameWork")}>
            <section className="flex flex-col gap-1">
              <h4 className="text-sm font-semibold">{t("ItemDialog.sameWork")}</h4>
              <LinkSearch type={type} summary={d.summary} corrections={corrections} />
            </section>
          </Part>
          <Part title={t("WorkScreen.duplicates")}>
            <section className="flex flex-col gap-1">
              <h4 className="text-sm font-semibold">{t("WorkScreen.duplicates")}</h4>
              <WorkDuplicates type={type} item={ref} sites={sites} corrections={corrections} />
            </section>
          </Part>
          <Part title={t("WorkScreen.relations")}>
            <WorkRelations type={type} item={ref} relations={d.relations} />
          </Part>
          <div>
            <RemoveItem summary={d.summary} typeId={type.id} ofSeveral={sites.length > 1} onRemoved={() => rest && onMove({ source: rest.source, item: rest.item })} />
          </div>
        </>
      )}
      {view === "evidence" && asked && (
        <Part title={asked.name}>
          <div className="flex items-center gap-2">
            <h3 className="min-w-0 flex-1 text-sm font-semibold break-words">{asked.name}</h3>
            <Button variant="ghost" size="icon-xs" title={t("WorkScreen.close")} aria-label={t("WorkScreen.close")} onClick={onLeave}>
              <XIcon />
            </Button>
          </div>
          <EvidenceBody type={type} item={ref} asked={asked} facets={d.allFacets} corrections={corrections} />
        </Part>
      )}
    </div>
  );
}

/** Why the prediction: of a graded work by the model that has not seen it, read for this view alone; the grades given under it. */
function WhyView({ type, details: d, leadTo }: { type: ContentTypeInfo; details: ItemDetails; leadTo: (feature: FeatureContribution) => (() => void) | undefined }) {
  const { t } = useTranslation("item");
  const ref = { source: d.summary.source, item: d.summary.item };
  const graded = d.summary.grade != null;
  const heldOut = useHeldOut(ref, graded);
  return (
    <>
      {graded && (heldOut.data === undefined || heldOut.data === FITTING) && !heldOut.isError ? (
        <Skeleton className="h-16 w-full" />
      ) : heldOut.data && heldOut.data !== FITTING ? (
        <Part title={t("ItemDialog.withoutGrade")}>
          <div className={cn("flex flex-col gap-1", heldOut.isFetching && "opacity-60")}>
            <div className="text-sm">
              <Trans
                t={t}
                i18nKey="ItemDialog.heldOutScore"
                values={{ score: score(Math.max(0, heldOut.data.score)) }}
                components={{ score: <span className="font-semibold tabular-nums" /> }}
              />
              {heldOut.data.gradePlace != null && (
                <span className="text-muted-foreground">
                  {" "}
                  ·{" "}
                  <Trans t={t} i18nKey="ItemDialog.gradePlace" values={{ place: score(heldOut.data.gradePlace) }} components={{ place: <span className="tabular-nums" /> }} />
                </span>
              )}
            </div>
            <Explanation groups={heldOut.data.explanation} title={t("ItemDialog.drivenBy")} unfolded onFeature={leadTo} />
          </div>
        </Part>
      ) : (
        <Part title={t("ItemDialog.whyPrediction")}>
          <Explanation groups={d.explanation} unfolded onFeature={leadTo} />
        </Part>
      )}
      {d.ratings.length > 0 && (
        <Part title={t("ItemDialog.myGrades")}>
          <Ratings type={type} details={d} />
        </Part>
      )}
    </>
  );
}
